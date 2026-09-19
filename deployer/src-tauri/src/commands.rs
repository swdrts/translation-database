use crate::autostart;
use crate::compose::{self, PullEvent};
use crate::config::{self, WizardConfig};
use crate::docker;
use crate::registry;
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
    /// 端口被占时为 8080..=8090 首个空闲端口（全占回 port 本身）；未被占时等于 port
    pub suggested_port: u16,
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

// compose 以编译期 include_str! 内嵌（resources/docker-compose.yml 由同步脚本保持新鲜），
// 避免 resource_dir() 在 dev/安装两种布局下的路径差异（手工验证发现的 os error 2）
const COMPOSE_YML: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/resources/docker-compose.yml"));

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

/// 失败知识库：常见 docker/compose 错误特征（小写 contains）→ 中文处置建议。
/// 未命中返回 None，调用方保持原错误文案不附建议。
pub fn failure_hint(msg: &str) -> Option<&'static str> {
    let m = msg.to_lowercase();
    if m.contains("port is already allocated") || m.contains("address already in use") {
        Some("端口被其他程序占用。请关闭占用程序，或更换端口后重试（管理窗口「设置」页也支持改端口）。")
    } else if m.contains("out of memory") || m.contains("cannot allocate memory") {
        Some("内存不足。请关闭其他大型程序后重试，或在配置步调小 ES/后端堆内存。")
    } else if m.contains("no space left") {
        Some("磁盘空间不足。请清理出至少 15GB 空间后重试。")
    } else if m.contains("i/o timeout") || m.contains("context deadline exceeded") || m.contains("connection refused") {
        Some("网络异常。请检查网络/代理连通性后重试；镜像拉取超时可稍后再试。")
    } else {
        None
    }
}

/// 失败知识库串联：Err 文案命中知识库时追加「建议：{hint}」，未命中原样返回
fn with_hint(msg: String) -> String {
    match failure_hint(&msg) {
        Some(hint) => format!("{msg}\n建议：{hint}"),
        None => msg,
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
    // 端口被占（bind Err 且非 PermissionDenied）时依次试 8080..=8090 取首个 bind 成功者，
    // 全部被占则回 port 本身（前端如实展示被占）；未被占（含 macOS PermissionDenied
    // 视为可用）时 suggested_port = port，不覆盖「视为可用」语义
    let suggested_port = if port_free {
        port
    } else {
        (8080..=8090).find(|p| std::net::TcpListener::bind(("127.0.0.1", *p)).is_ok()).unwrap_or(port)
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
        suggested_port,
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
            return Err(with_hint(install_error_hint(&install_res)));
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

/// 返回值：Ok(true)=检测到既有 .env 复用了旧三密钥（本轮输入的管理员密码未生效，
/// 前端完成页须提示「沿用首次部署」而非展示本轮密码）；Ok(false)=全新生成
#[tauri::command]
pub async fn save_config(st: State<'_, AppState>, config: WizardConfig) -> Result<bool, String> {
    config::validate(&config).map_err(|e| e.join("；"))?;
    let mut config = config;
    // 卸载保留数据卷后重部署（或断点续跑重提交配置）时必须复用 .env 三密钥：
    // 重新生成会使 JWT/DB 密钥与旧 pgdata 卷失配（postgres 密码仅卷为空时生效），backend 连不上库起不来
    let old_env = std::fs::read_to_string(st.data_dir.join(".env")).unwrap_or_default();
    let (jwt, db_pw, reused) = match config::reuse_secrets(&old_env) {
        // 管理员密码沿用首次部署所设（后端 admin 已存在时不改密），用户本轮输入仅在全新部署生效
        Some((jwt, db_pw, admin)) => { config.admin_password = admin; (jwt, db_pw, true) }
        None => (config::generate_jwt_secret(), config::generate_db_password(), false),
    };
    let env = config::render_env(&config, &jwt, &db_pw);
    compose::materialize_project(&st.data_dir, COMPOSE_YML, &env).map_err(|e| e.to_string())?;
    let mut ps = st.state.lock().unwrap();
    ps.config = Some(config);
    ps.stage = DeployStage::Pull;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(reused)
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
        return Err(with_hint(format!("镜像拉取失败：{}", pull_out.stderr.trim())));
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
        return Err(with_hint(format!("容器启动失败：{}", up_out.stderr.trim())));
    }
    // 规格三层自启之二「工具自身默认开（托盘/设置页可关）」：首次部署成功时 enable 一次并随本次
    // save 落 autostart_done 标记；此后（含重部署）不再自动打开，尊重用户在设置页的主动关闭
    let first_autostart = {
        let mut ps = st.state.lock().unwrap();
        ps.stage = DeployStage::Done;
        ps.deployed = true;
        let first = !ps.autostart_done;
        ps.autostart_done = true;
        state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
        first
    };
    // 锁外调 autostart 插件（不持 state 锁）；默认开启失败不阻塞部署，设置页可再开
    if first_autostart {
        use tauri_plugin_autostart::ManagerExt;
        let _ = app.autolaunch().enable();
    }
    // 规格三层自启的 Docker 层：部署成功即开启 Docker Desktop 登录自启（fire-and-forget，失败不影响部署结果）
    let runner_for_autostart = st.runner.clone();
    tauri::async_runtime::spawn(async move {
        let _ = autostart::set_docker_autostart(runner_for_autostart.as_ref(), true).await;
    });
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
    let out = compose::logs(st.runner.as_ref(), &st.data_dir, &service).await;
    if out.success() { Ok(out.stdout) } else { Err(out.stderr.trim().to_string()) }
}

/// 解析 docker inspect 的版本 label 输出：空串/<no value>（label 缺失时 Go 模板输出）→ None
pub fn parse_version_label(inspect: &RunOutput) -> Option<String> {
    let v = inspect.stdout.trim().to_string();
    (!v.is_empty() && v != "<no value>").then_some(v)
}

/// 维护页「升级」核心流程：.env 版本临时置 latest → pull 最新镜像 → up -d --wait 重建
/// → docker inspect 读回实际版本号并回写 .env。返回 (新 .env 内容, 实际版本号)；
/// 镜像未打版本 label 时保留 latest 并返回 None（回退分支，不阻塞升级成功）
pub async fn upgrade_flow(
    runner: &dyn CommandRunner,
    project_dir: &std::path::Path,
    old_env: &str,
) -> Result<(String, Option<String>), String> {
    // 拉取/重建阶段钉在 latest：发布流程保证 latest 与最新版本 tag 同源
    let latest_env = config::env_with_version(old_env, "latest");
    let out = compose::pull(runner, project_dir, |_| {}).await;
    if !out.success() { return Err(format!("拉取失败：{}", out.stderr.trim())); }
    let up = compose::up_wait(runner, project_dir, |_| {}).await;
    if !up.success() { return Err(format!("重建失败：{}", up.stderr.trim())); }
    // 从 backend 容器镜像读回实际版本：label 是发布时写入的权威版本号；
    // 读不到（旧镜像未打 label）时保留 latest
    let inspect = runner
        .run(CmdSpec::new("docker", &[
            "inspect", "--format", "{{ index .Config.Labels \"org.opencontainers.image.version\" }}",
            "transdb-backend",
        ]))
        .await;
    let version = parse_version_label(&inspect);
    let new_env = match &version {
        Some(v) => config::env_with_version(&latest_env, v),
        None => latest_env,
    };
    Ok((new_env, version))
}

/// 维护页「升级」：pull 最新镜像后 up -d --wait 重建，进度复用 deploy://progress 事件；
/// 升级完成后把实际版本号回写 .env 与持久化配置，前端可展示「已更新到 vX.Y.Z」
#[tauri::command]
pub async fn upgrade_stack(app: AppHandle, st: State<'_, AppState>) -> Result<Option<String>, String> {
    emit(&app, ProgressEvent { stage: "pull".into(), message: "拉取镜像更新…".into(), pull: None, download: None });
    let old_env = std::fs::read_to_string(st.data_dir.join(".env")).unwrap_or_default();
    let app_h = app.clone();
    // 回调直接传闭包而非 Box::new（brief 原文）：闭包经 Box::new 泛型中转后
    // 生命周期被提前固定，无法满足 for<'a> Fn(&'a str) 高阶约束（E0658 类报错）
    let latest_env = config::env_with_version(&old_env, "latest");
    compose::materialize_project(&st.data_dir, COMPOSE_YML, &latest_env).map_err(|e| e.to_string())?;
    let out = compose::pull(st.runner.as_ref(), &st.data_dir, move |l| {
        if let Some(ev) = compose::parse_pull_line(l) {
            emit(&app_h, ProgressEvent { stage: "pull".into(), message: format!("{} {}", ev.service, ev.phase), pull: Some(ev), download: None });
        }
    }).await;
    if !out.success() {
        // 拉取失败：恢复原 .env（钉回原版本号），下次 restart/up 仍是升级前的镜像
        let _ = compose::materialize_project(&st.data_dir, COMPOSE_YML, &old_env);
        return Err(format!("拉取失败：{}", out.stderr.trim()));
    }
    emit(&app, ProgressEvent { stage: "up".into(), message: "重建容器（PG/ES 就绪后自动起后端，约 1-2 分钟）…".into(), pull: None, download: None });
    let app_h = app.clone();
    let up = compose::up_wait(st.runner.as_ref(), &st.data_dir, move |l| {
        emit(&app_h, ProgressEvent { stage: "up".into(), message: l.to_string(), pull: None, download: None });
    }).await;
    if !up.success() { return Err(format!("重建失败：{}", up.stderr.trim())); }
    // 从 backend 容器镜像读回实际版本号并回写 .env：日常 restart/up 钉在具体版本，
    // latest 只在点「升级」那一刻生效；旧镜像未打 label 时保留 latest（不影响升级结果）
    let inspect = st.runner.as_ref()
        .run(CmdSpec::new("docker", &[
            "inspect", "--format", "{{ index .Config.Labels \"org.opencontainers.image.version\" }}",
            "transdb-backend",
        ]))
        .await;
    let version = parse_version_label(&inspect);
    let final_env = match &version {
        Some(v) => config::env_with_version(&latest_env, v),
        None => latest_env,
    };
    let _ = compose::materialize_project(&st.data_dir, COMPOSE_YML, &final_env);
    if let Some(v) = &version {
        let mut ps = st.state.lock().unwrap();
        if let Some(c) = ps.config.as_mut() { c.app_version = v.clone(); }
        state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    }
    Ok(version)
}

/// 维护页「卸载」：down 停止并删除容器；remove_data=true 时 down -v 连数据卷一起删，
/// 并连带删除部署产物 .env/docker-compose.yml。无论是否删卷，state 一律重置为初始态
/// （磁盘+内存）：保留数据的卸载同样没有「完成页」可言（容器已删，旧 URL 指向空处），
/// 重开部署器回到向导起点；保留的 pgdata/esdata 数据卷由 save_config 的密钥复用衔接，
/// 重部署后数据与账号仍有效。
#[tauri::command]
pub async fn uninstall(st: State<'_, AppState>, remove_data: bool) -> Result<(), String> {
    let out = compose::down(st.runner.as_ref(), &st.data_dir, remove_data).await;
    if !out.success() { return Err(format!("卸载失败：{}", out.stderr.trim())); }
    if remove_data {
        // 全新部署须走密钥新生成，残留旧 .env 会让 save_config 复用旧密钥、新设管理员密码被覆写；
        // Err 忽略——文件不存在不算失败
        let _ = std::fs::remove_file(st.data_dir.join(".env"));
        let _ = std::fs::remove_file(st.data_dir.join("docker-compose.yml"));
    }
    let fresh = state::PersistedState::initial();
    state::save(&st.data_dir, &fresh).map_err(|e| e.to_string())?;
    *st.state.lock().unwrap() = fresh;
    Ok(())
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

#[derive(Serialize)]
pub struct RegistryMirrorsInfo {
    pub path: String,
    pub mirrors: Vec<String>,
    pub defaults: Vec<String>,
}

#[derive(Serialize)]
pub struct MirrorProbe {
    pub reachable: bool,
    pub status: u16,
}

#[tauri::command]
pub async fn get_registry_mirrors() -> Result<RegistryMirrorsInfo, String> {
    let path = registry::daemon_json_path();
    Ok(RegistryMirrorsInfo {
        path: path.to_string_lossy().into_owned(),
        mirrors: registry::read_mirrors(&path),
        defaults: registry::DEFAULT_MIRRORS.iter().map(|s| s.to_string()).collect(),
    })
}

/// 保存镜像加速并重启 Docker 生效：引擎当前在运行才重启（未运行时下次启动自然生效），
/// 重启成功后从引擎读回实际生效值返回
#[tauri::command]
pub async fn set_registry_mirrors(app: AppHandle, st: State<'_, AppState>, mirrors: Vec<String>) -> Result<Vec<String>, String> {
    let mirrors = registry::normalize(mirrors);
    if mirrors.len() > 10 {
        return Err("镜像加速地址最多 10 个".into());
    }
    for m in &mirrors {
        if !registry::validate_mirror(m) {
            return Err(format!("镜像地址格式不正确：{m}（需以 http:// 或 https:// 开头，且不含空格）"));
        }
    }
    let path = registry::daemon_json_path();
    registry::write_mirrors(&path, &mirrors)?;
    if !docker::probe(st.runner.as_ref()).await.engine_ready {
        return Ok(mirrors);
    }
    emit(&app, ProgressEvent { stage: "mirrors".into(), message: "正在重启 Docker 以应用镜像加速配置…".into(), pull: None, download: None });
    if !docker::restart_desktop(st.runner.as_ref()).await {
        return Err("配置已保存，但重启 Docker Desktop 失败：请手动重启 Docker Desktop 使镜像配置生效".into());
    }
    let app_h = app.clone();
    let ready = docker::wait_engine(st.runner.as_ref(), Duration::from_secs(180), Duration::from_secs(2), move |tick| {
        emit(&app_h, ProgressEvent { stage: "mirrors".into(), message: format!("等待 Docker 重启就绪…（已等待 {} 秒）", tick * 2), pull: None, download: None });
    }).await;
    if !ready {
        return Err("Docker 重启后长时间未就绪：请打开 Docker Desktop 查看其状态后重试".into());
    }
    Ok(docker::effective_mirrors(st.runner.as_ref()).await)
}

/// 探测镜像源存活：/v2/ 返回 401（鉴权质询）或 200 即视为可用。
/// 网络错误不抛 Err，统一报 reachable=false，便于前端一次展示全部结果对比
#[tauri::command]
pub async fn probe_registry_mirror(url: String) -> Result<MirrorProbe, String> {
    let base = url.trim().trim_end_matches('/');
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
        .map_err(|e| e.to_string())?;
    match client.get(format!("{base}/v2/")).send().await {
        Ok(r) => {
            let status = r.status().as_u16();
            Ok(MirrorProbe { reachable: matches!(status, 200 | 401), status })
        }
        Err(_) => Ok(MirrorProbe { reachable: false, status: 0 }),
    }
}

/// 改端口：只重建 frontend（端口映射只挂它身上），其余容器不动。
/// 红线：绝不重新生成 JWT/DB 密钥——重生成会使既有数据卷失配（postgres 记住的
/// 是旧 DB 密码、已签发 token 校验的是旧 JWT），故三项密钥从旧 .env 原样读回。
#[tauri::command]
pub async fn change_port(st: State<'_, AppState>, port: u16) -> Result<(), String> {
    // 同时取 autostart_done：下方整态覆写重建 PersistedState 时保留该标记，
    // 避免改端口后下次部署把用户已主动关闭的工具自启又默认打开
    let (cfg, autostart_done) = {
        let ps = st.state.lock().unwrap();
        (ps.config.clone().ok_or("尚未部署，无需改端口")?, ps.autostart_done)
    };
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
    compose::materialize_project(&st.data_dir, COMPOSE_YML, &env).map_err(|e| e.to_string())?;
    let out = st.runner.run(CmdSpec::new(
        "docker",
        &["compose", "-p", compose::PROJECT_NAME, "--project-directory",
          st.data_dir.to_string_lossy().as_ref(), "up", "-d", "frontend"],
    )).await;
    if !out.success() { return Err(format!("重建前端容器失败：{}", out.stderr.trim())); }
    *st.state.lock().unwrap() = state::PersistedState { stage: state::DeployStage::Done, deployed: true, config: Some(next), autostart_done };
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

    #[tokio::test]
    async fn upgrade_flow_writes_latest_then_pinned_version() {
        let f = crate::runner::FakeRunner::default();
        // pull（streaming）、up（streaming）、inspect（读版本 label）
        f.enqueue(crate::runner::RunOutput::ok("backend Pulled\n"));
        f.enqueue(crate::runner::RunOutput::ok("Container transdb-backend-1 Healthy\n"));
        f.enqueue(crate::runner::RunOutput::ok("0.2.0"));
        let old_env = "TRANSDB_FRONTEND_PORT=80\nTRANSDB_APP_VERSION=0.1.0\n";
        let (new_env, version) = upgrade_flow(&f, &std::path::PathBuf::from("."), old_env).await.unwrap();
        assert_eq!(version.as_deref(), Some("0.2.0"));
        // 升级完成后回写实际版本号（而非停留在 latest）
        assert!(new_env.contains("TRANSDB_APP_VERSION=0.2.0\n"));
        let calls = f.calls_snapshot();
        let inspect = calls.iter().find(|c| c.args.contains(&"inspect".to_string())).unwrap();
        assert!(inspect.args.iter().any(|a| a.contains("org.opencontainers.image.version")));
        // pull/up 的 compose 调用发生在 inspect 之前
        let inspect_idx = calls.iter().position(|c| c.args.contains(&"inspect".to_string())).unwrap();
        let pull_idx = calls.iter().position(|c| c.args.contains(&"pull".to_string())).unwrap();
        assert!(pull_idx < inspect_idx);
    }

    #[tokio::test]
    async fn upgrade_flow_falls_back_to_latest_when_label_missing() {
        let f = crate::runner::FakeRunner::default();
        f.enqueue(crate::runner::RunOutput::ok(""));
        f.enqueue(crate::runner::RunOutput::ok(""));
        f.enqueue(crate::runner::RunOutput::ok(""));
        let old_env = "TRANSDB_APP_VERSION=0.1.0\n";
        let (new_env, version) = upgrade_flow(&f, &std::path::PathBuf::from("."), old_env).await.unwrap();
        // label 缺失：保留 latest（回退分支不改写），version 返回 None
        assert!(new_env.contains("TRANSDB_APP_VERSION=latest\n"));
        assert_eq!(version, None);
    }

    #[tokio::test]
    async fn upgrade_flow_propagates_pull_failure() {
        let f = crate::runner::FakeRunner::default();
        f.enqueue(crate::runner::RunOutput::fail(1, "network error"));
        let err = upgrade_flow(&f, &std::path::PathBuf::from("."), "TRANSDB_APP_VERSION=0.1.0\n").await.unwrap_err();
        assert!(err.contains("network error"));
    }

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

    #[test]
    fn failure_hint_maps_common_docker_errors() {
        assert!(failure_hint("Bind for 0.0.0.0:80 failed: port is already allocated").is_some());
        assert!(failure_hint("no space left on device").unwrap().contains("磁盘"));
        assert!(failure_hint("i/o timeout").unwrap().contains("网络"));
        assert!(failure_hint("something else").is_none());
    }
}
