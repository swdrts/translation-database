use crate::compose::{self, PullEvent};
use crate::config::{self, WizardConfig};
use crate::docker;
use crate::runner::{CmdSpec, CommandRunner, RunOutput};
use crate::state::{self, DeployStage, PersistedState};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_opener::OpenerExt;

pub struct AppState {
    pub runner: Arc<dyn CommandRunner>,
    pub data_dir: PathBuf,
    pub state: Mutex<PersistedState>,
}

#[derive(Serialize)]
pub struct EnvReport {
    pub os_ok: bool,
    pub mem_gb: f32,
    pub mem_ok: bool,
    pub disk_free_gb: f32,
    pub disk_ok: bool,
    pub net_ok: bool,
    pub port: u16,
    pub port_free: bool,
}

#[derive(Serialize, Clone)]
pub struct ProgressEvent {
    pub stage: String,
    pub message: String,
    pub pull: Option<PullEvent>,
    pub download: Option<DownloadProgress>,
}

#[derive(Serialize, Clone)]
pub struct DownloadProgress { pub downloaded: u64, pub total: u64 }

#[derive(Serialize)]
pub struct DeployOutcome { pub url: String }

fn emit(app: &AppHandle, ev: ProgressEvent) {
    let _ = app.emit("deploy://progress", ev);
}

pub fn install_error_hint(out: &RunOutput) -> String {
    let msg = format!("{}{}", out.stdout, out.stderr).to_lowercase();
    if msg.contains("wsl") {
        format!("Docker 安装失败：缺少 WSL2 组件。{}", "请点击「一键安装 WSL2」，安装完成后重启电脑再重试。")
    } else if msg.contains("space") {
        "Docker 安装失败：磁盘空间不足。".into()
    } else {
        format!("Docker 安装失败：{}", out.stderr.trim())
    }
}

pub fn deploy_url(port: u16) -> String {
    if port == 80 { "http://localhost".into() } else { format!("http://localhost:{port}") }
}

#[tauri::command]
pub async fn get_app_state(st: State<'_, AppState>) -> Result<PersistedState, String> {
    // 每次从磁盘读取：启动期的内存快照可能因 load 失败退化为初始态，磁盘才是权威来源；
    // 读取失败（如权限被拒）上抛前端，避免静默回退把 deployed=true 误重置
    state::load(&st.data_dir).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn check_env(app: AppHandle, st: State<'_, AppState>) -> Result<EnvReport, String> {
    let port = st.state.lock().unwrap().config.as_ref().map(|c| c.port).unwrap_or(80);
    // macOS 非 root bind <1024 会报 PermissionDenied，但部署时容器由 Docker 的特权端口代理绑定，
    // 该错误不代表端口被占用，需视为可用；其余 bind 失败（AddrInUse 等）才判占用
    let port_free = match std::net::TcpListener::bind(("127.0.0.1", port)) {
        Ok(_) => true,
        Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => true,
        Err(_) => false,
    };
    let os_ok = cfg!(any(windows, target_os = "macos"));
    let mut sys = sysinfo::System::new_all();
    sys.refresh_all();
    let mem_gb = sys.total_memory() as f32 / 1024.0 / 1024.0 / 1024.0;
    let disk_free_gb = sysinfo::Disks::new_with_refreshed_list()
        .list().iter()
        .map(|d| d.available_space())
        .max().unwrap_or(0) as f32 / 1024.0 / 1024.0 / 1024.0;
    let net_ok = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build().map_err(|e| e.to_string())?
        .head("https://desktop.docker.com").send().await
        .map(|r| r.status().is_success() || r.status().as_u16() == 403)
        .unwrap_or(false);
    let report = EnvReport {
        os_ok,
        mem_gb,
        mem_ok: mem_gb >= 7.5,
        disk_free_gb,
        disk_ok: disk_free_gb >= 15.0,
        net_ok,
        port,
        port_free,
    };
    emit(&app, ProgressEvent { stage: "check_env".into(), message: format!("环境检测完成：内存 {mem_gb:.1}GB，磁盘 {disk_free_gb:.1}GB"), pull: None, download: None });
    Ok(report)
}

#[tauri::command]
pub async fn docker_probe(st: State<'_, AppState>) -> Result<docker::DockerStatus, String> {
    Ok(docker::probe(st.runner.as_ref()).await)
}

#[tauri::command]
pub async fn ensure_docker(app: AppHandle, st: State<'_, AppState>) -> Result<docker::DockerStatus, String> {
    let mut status = docker::probe(st.runner.as_ref()).await;
    if !status.installed {
        emit(&app, ProgressEvent { stage: "install_docker".into(), message: "开始下载 Docker Desktop 安装包…".into(), pull: None, download: None });
        let url = docker::installer_url(std::env::consts::OS, std::env::consts::ARCH);
        let dest = st.data_dir.join(if cfg!(windows) { "DockerDesktopInstaller.exe" } else { "Docker.dmg" });
        let app_h = app.clone();
        docker::download_file(&url, &dest, move |d, t| {
            emit(&app_h, ProgressEvent { stage: "install_docker".into(), message: "正在下载 Docker Desktop".into(), pull: None, download: Some(DownloadProgress { downloaded: d, total: t }) });
        }).await?;
        emit(&app, ProgressEvent { stage: "install_docker".into(), message: "下载完成，开始安装（可能弹出系统确认框）…".into(), pull: None, download: None });
        let app_h = app.clone();
        let install_res = {
            #[cfg(windows)]
            { docker::install_windows(st.runner.as_ref(), &dest, move |l| emit(&app_h, ProgressEvent { stage: "install_docker".into(), message: l.to_string(), pull: None, download: None })).await }
            #[cfg(target_os = "macos")]
            { match docker::install_macos(st.runner.as_ref(), &dest, move |l| emit(&app_h, ProgressEvent { stage: "install_docker".into(), message: l.to_string(), pull: None, download: None })).await {
                Ok(()) => RunOutput::ok("installed"),
                Err(e) => RunOutput::fail(1, &e),
            } }
        };
        if !install_res.success() {
            return Err(install_error_hint(&install_res));
        }
        status = docker::probe(st.runner.as_ref()).await;
        // Windows 全新安装后，安装器写入的 PATH 只对新进程生效——本进程的环境变量是启动时快照，
        // docker.exe 仍探测不到（installed=false）。此时不能进入 start+wait 600 秒空等，
        // 直接引导用户重启电脑（新进程拿到新 PATH）后重开部署器续跑
        #[cfg(windows)]
        if !status.installed {
            return Err("Docker 安装完成，请重启电脑后重新打开部署器继续".into());
        }
    }
    if !status.engine_ready {
        emit(&app, ProgressEvent { stage: "wait_engine".into(), message: "正在启动 Docker 引擎（首次约 30-60 秒）…".into(), pull: None, download: None });
        #[cfg(target_os = "macos")]
        emit(&app, ProgressEvent { stage: "wait_engine".into(), message: "若弹出 Docker 服务条款窗口，请点击「接受」".into(), pull: None, download: None });
        if !status.installed || docker::start_docker_desktop(st.runner.as_ref()).await {
            let app_h = app.clone();
            let ready = docker::wait_engine(st.runner.as_ref(), Duration::from_secs(600), Duration::from_secs(2), move |tick| {
                emit(&app_h, ProgressEvent { stage: "wait_engine".into(), message: format!("等待引擎就绪…（已等待 {} 秒）", tick * 2), pull: None, download: None });
            }).await;
            if !ready {
                return Err("Docker 引擎长时间未就绪。请打开 Docker Desktop 查看其界面提示后重试。".into());
            }
        } else {
            return Err("启动 Docker Desktop 失败，请手动打开它后点击重试。".into());
        }
        status = docker::DockerStatus { installed: true, engine_ready: true };
    }
    let mut ps = st.state.lock().unwrap();
    ps.stage = DeployStage::Configure;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(status)
}

#[tauri::command]
pub async fn install_wsl2(st: State<'_, AppState>) -> Result<(), String> {
    let out = st.runner.run(docker::wsl_install_spec()).await;
    if out.success() { Ok(()) } else { Err(format!("WSL2 安装命令启动失败：{}", out.stderr)) }
}

#[tauri::command]
pub async fn save_config(st: State<'_, AppState>, config: WizardConfig) -> Result<(), String> {
    config::validate(&config).map_err(|e| e.join("；"))?;
    let env = config::render_env(&config, &config::generate_jwt_secret(), &config::generate_db_password());
    // compose 以编译期 include_str! 内嵌（resources/docker-compose.yml 由同步脚本保持新鲜），
    // 避免 resource_dir() 在 dev/安装两种布局下的路径差异（手工验证发现的 os error 2）
    const COMPOSE_YML: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/resources/docker-compose.yml"));
    compose::materialize_project(&st.data_dir, COMPOSE_YML, &env).map_err(|e| e.to_string())?;
    let mut ps = st.state.lock().unwrap();
    ps.config = Some(config);
    ps.stage = DeployStage::Pull;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn start_deploy(app: AppHandle, st: State<'_, AppState>) -> Result<DeployOutcome, String> {
    let port = {
        let ps = st.state.lock().unwrap();
        match &ps.config {
            Some(c) => c.port,
            None => return Err("尚未保存部署配置，请先完成配置步骤".into()),
        }
    };
    emit(&app, ProgressEvent { stage: "pull".into(), message: "开始拉取镜像（约 1.7GB，视网速）…".into(), pull: None, download: None });
    let app_h = app.clone();
    let pull_out = compose::pull(st.runner.as_ref(), &st.data_dir, move |line| {
        if let Some(ev) = compose::parse_pull_line(line) {
            emit(&app_h, ProgressEvent { stage: "pull".into(), message: format!("{} {}", ev.service, ev.phase), pull: Some(ev), download: None });
        }
    }).await;
    if !pull_out.success() {
        return Err(format!("镜像拉取失败：{}", pull_out.stderr.trim()));
    }
    {
        let mut ps = st.state.lock().unwrap();
        ps.stage = DeployStage::Up;
        state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    }
    emit(&app, ProgressEvent { stage: "up".into(), message: "启动容器（PG/ES 就绪后自动起后端，约 1-2 分钟）…".into(), pull: None, download: None });
    let app_h = app.clone();
    let up_out = compose::up_wait(st.runner.as_ref(), &st.data_dir, move |line| {
        emit(&app_h, ProgressEvent { stage: "up".into(), message: line.to_string(), pull: None, download: None });
    }).await;
    if !up_out.success() {
        return Err(format!("容器启动失败：{}", up_out.stderr.trim()));
    }
    let mut ps = st.state.lock().unwrap();
    ps.stage = DeployStage::Done;
    ps.deployed = true;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(DeployOutcome { url: deploy_url(port) })
}

/// 统一的「打开翻译数据库网页」入口：port 取自持久化配置（未部署/读不到 state 时 80）。
/// command `open_web` 与托盘 MENU_OPEN_WEB 分支共用，保证两处 URL 计算一致。
pub fn open_web_now(app: &AppHandle) -> Result<(), String> {
    let port = app
        .try_state::<AppState>()
        .and_then(|st| st.state.lock().unwrap().config.as_ref().map(|c| c.port))
        .unwrap_or(80);
    // opener 2.5.5 的 open_url 带 with: Option<impl Into<String>> 参数（brief 为单参数旧签名）
    app.opener().open_url(deploy_url(port), None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn open_web(app: AppHandle) -> Result<(), String> {
    open_web_now(&app)
}

#[tauri::command]
pub async fn refresh_status(st: State<'_, AppState>) -> Result<crate::tray::StatusPayload, String> {
    // 与托盘 30s 心跳同一 snapshot 来源，管理窗口 10s 兜底轮询复用
    let (engine_ready, containers) = crate::tray::snapshot(st.runner.as_ref(), &st.data_dir).await;
    Ok(crate::tray::StatusPayload { engine_ready, containers })
}

#[tauri::command]
pub async fn stack_op(st: State<'_, AppState>, op: String) -> Result<(), String> {
    let r = st.runner.as_ref();
    let out = match op.as_str() {
        "start" => compose::start(r, &st.data_dir).await,
        "stop" => compose::stop(r, &st.data_dir).await,
        "restart" => compose::restart(r, &st.data_dir).await,
        _ => return Err(format!("未知操作：{op}")),
    };
    if out.success() { Ok(()) } else { Err(format!("操作失败：{}", out.stderr.trim())) }
}

#[tauri::command]
pub async fn container_logs(st: State<'_, AppState>, service: String) -> Result<String, String> {
    Ok(compose::logs(st.runner.as_ref(), &st.data_dir, &service).await)
}

#[tauri::command]
pub async fn open_dashboard_window(app: AppHandle) -> Result<(), String> {
    let w = app.get_webview_window("dashboard").ok_or("管理窗口不存在")?;
    w.show().map_err(|e| e.to_string())?;
    w.set_focus().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn web_url(st: State<'_, AppState>) -> Result<String, String> {
    let port = st.state.lock().unwrap().config.as_ref().map(|c| c.port).unwrap_or(80);
    Ok(deploy_url(port))
}

#[tauri::command]
pub async fn reset_state(st: State<'_, AppState>) -> Result<(), String> {
    let _ = std::fs::remove_file(st.data_dir.join("state.json"));
    *st.state.lock().unwrap() = PersistedState::initial();
    Ok(())
}

/// 三层自启之二：工具自身开机自启（Windows Run 键 / macOS LaunchAgent），走 autostart 插件。
#[tauri::command]
pub async fn set_tool_autostart(app: AppHandle, enabled: bool) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    let m = app.autolaunch();
    if enabled { m.enable().map_err(|e| e.to_string()) } else { m.disable().map_err(|e| e.to_string()) }
}

#[tauri::command]
pub async fn get_tool_autostart(app: AppHandle) -> Result<bool, String> {
    use tauri_plugin_autostart::ManagerExt;
    Ok(app.autolaunch().is_enabled().unwrap_or(false))
}

/// 改端口：只重建 frontend（端口映射只挂它身上），其余容器不动。
/// 红线：绝不重新生成 JWT/DB 密钥——重生成会使既有数据卷失配（postgres 记住的
/// 是旧 DB 密码、已签发 token 校验的是旧 JWT），故三项密钥从旧 .env 原样读回。
#[tauri::command]
pub async fn change_port(st: State<'_, AppState>, port: u16) -> Result<(), String> {
    let cfg = { st.state.lock().unwrap().config.clone().ok_or("尚未部署，无需改端口")? };
    let mut next = cfg;
    next.port = port;
    let old = std::fs::read_to_string(st.data_dir.join(".env")).map_err(|e| e.to_string())?;
    let find = |k: &str| {
        old.lines().find(|l| l.starts_with(k)).and_then(|l| l.split_once('=').map(|(_, v)| v.to_string())).unwrap_or_default()
    };
    let jwt = find("TRANSDB_JWT_SECRET=");
    let db_pw = find("TRANSDB_DB_PASSWORD=");
    // state.json 落盘时已剥密码（config 里是空串）——从 .env 回填真值后再校验/渲染
    next.admin_password = find("TRANSDB_ADMIN_PASSWORD=");
    if jwt.is_empty() || db_pw.is_empty() || next.admin_password.is_empty() {
        return Err("旧 .env 密钥读取失败，为避免破坏已有数据已拒绝改端口".into());
    }
    config::validate(&next).map_err(|e| e.join("；"))?;
    // 与 check_env 同款：macOS 非 root bind <1024 报 PermissionDenied 不代表被占用
    //（容器端口由 Docker 特权代理绑定），其余 bind 失败（AddrInUse 等）才判占用
    match std::net::TcpListener::bind(("127.0.0.1", port)) {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {}
        Err(_) => return Err(format!("端口 {port} 已被占用")),
    }
    let env = config::render_env(&next, &jwt, &db_pw);
    const COMPOSE_YML: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/resources/docker-compose.yml"));
    compose::materialize_project(&st.data_dir, COMPOSE_YML, &env).map_err(|e| e.to_string())?;
    let out = st.runner.run(CmdSpec::new(
        "docker",
        &["compose", "-p", compose::PROJECT_NAME, "--project-directory",
          st.data_dir.to_string_lossy().as_ref(), "up", "-d", "frontend"],
    )).await;
    if !out.success() { return Err(format!("重建前端容器失败：{}", out.stderr.trim())); }
    *st.state.lock().unwrap() = state::PersistedState { stage: state::DeployStage::Done, deployed: true, config: Some(next) };
    // save 落盘会剥 admin_password（state.json 不存明文），内存态保留真值
    state::save(&st.data_dir, &st.state.lock().unwrap().clone()).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn open_data_dir(app: AppHandle, st: State<'_, AppState>) -> Result<(), String> {
    app.opener().open_path(st.data_dir.to_string_lossy().to_string(), None::<&str>).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn friendly_install_error_detects_wsl() {
        let e = RunOutput::fail(1, "the installer requires WSL 2 to be installed");
        assert!(install_error_hint(&e).contains("WSL2"));
        let e2 = RunOutput::fail(1, "some other failure");
        assert!(!install_error_hint(&e2).contains("WSL2"));
    }

    #[test]
    fn deploy_url_reflects_port() {
        assert_eq!(deploy_url(80), "http://localhost");
        assert_eq!(deploy_url(8080), "http://localhost:8080");
    }
}
