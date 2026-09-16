use crate::compose::{self, PullEvent};
use crate::config::{self, WizardConfig};
use crate::docker;
use crate::runner::{CommandRunner, RunOutput};
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

fn stage_name(s: &DeployStage) -> String {
    serde_json::to_value(s).ok()
        .and_then(|v| v.get("stage").and_then(|s| s.as_str().map(String::from)))
        .unwrap_or_default()
}

#[tauri::command]
pub async fn get_app_state(st: State<'_, AppState>) -> Result<PersistedState, String> {
    Ok(st.state.lock().unwrap().clone())
}

#[tauri::command]
pub async fn check_env(app: AppHandle, st: State<'_, AppState>) -> Result<EnvReport, String> {
    let port = st.state.lock().unwrap().config.as_ref().map(|c| c.port).unwrap_or(80);
    let port_free = std::net::TcpListener::bind(("127.0.0.1", port)).is_ok();
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
pub async fn save_config(app: AppHandle, st: State<'_, AppState>, config: WizardConfig) -> Result<(), String> {
    config::validate(&config).map_err(|e| e.join("；"))?;
    let env = config::render_env(&config, &config::generate_jwt_secret(), &config::generate_db_password());
    let compose_yml = std::fs::read_to_string(
        app.path().resource_dir().map_err(|e| e.to_string())?.join("docker-compose.yml"),
    ).map_err(|e| format!("读取内置 compose 资源失败：{e}"))?;
    compose::materialize_project(&st.data_dir, &compose_yml, &env).map_err(|e| e.to_string())?;
    let mut ps = st.state.lock().unwrap();
    ps.config = Some(config);
    ps.stage = DeployStage::Pull;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn start_deploy(app: AppHandle, st: State<'_, AppState>) -> Result<DeployOutcome, String> {
    let port = st.state.lock().unwrap().config.as_ref().map(|c| c.port).unwrap_or(80);
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

#[tauri::command]
pub async fn open_web(app: AppHandle, url: String) -> Result<(), String> {
    // opener 2.5.5 的 open_url 带 with: Option<impl Into<String>> 参数（brief 为单参数旧签名）
    app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn reset_state(st: State<'_, AppState>) -> Result<(), String> {
    let _ = std::fs::remove_file(st.data_dir.join("state.json"));
    *st.state.lock().unwrap() = PersistedState::initial();
    Ok(())
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
