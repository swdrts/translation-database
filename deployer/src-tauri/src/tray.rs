use crate::compose::ContainerStatus;
use crate::runner::CmdSpec;
use crate::runner::CommandRunner;
use std::sync::Arc;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager};

pub const MENU_OPEN_WEB: &str = "open_web";
pub const MENU_OPEN_DASHBOARD: &str = "open_dashboard";
pub const MENU_START: &str = "stack_start";
pub const MENU_STOP: &str = "stack_stop";
pub const MENU_RESTART: &str = "stack_restart";
pub const MENU_TRY_DOCKER: &str = "try_docker";
pub const MENU_QUIT: &str = "quit";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DotColor { Green, Yellow, Red }

impl DotColor {
    fn rgb(self) -> (u8, u8, u8) {
        match self {
            DotColor::Green => (82, 196, 26),
            DotColor::Yellow => (230, 162, 60),
            DotColor::Red => (245, 108, 108),
        }
    }
}

/// 32×32 实心圆 RGBA：中心 (15.5,15.5) 半径 14，抗锯齿边缘 1px
pub fn dot_icon(color: DotColor) -> tauri::image::Image<'static> {
    let (r, g, b) = color.rgb();
    let mut rgba = Vec::with_capacity(32 * 32 * 4);
    for y in 0..32i32 {
        for x in 0..32i32 {
            let d = ((x as f32 - 15.5).powi(2) + (y as f32 - 15.5).powi(2)).sqrt();
            let a = if d <= 13.0 { 255 } else if d <= 14.0 { ((14.0 - d) * 255.0) as u8 } else { 0 };
            rgba.extend_from_slice(&[r, g, b, a]);
        }
    }
    tauri::image::Image::new_owned(rgba, 32, 32)
}

pub fn current_color(containers: &[ContainerStatus], engine_ready: bool) -> DotColor {
    if !engine_ready || containers.iter().any(|c| c.state != "running") {
        return DotColor::Red;
    }
    let any_starting = containers.iter().any(|c| matches!(c.health.as_deref(), Some("starting") | None));
    let all_healthy = containers.iter().all(|c| matches!(c.health.as_deref(), Some("healthy") | None));
    if all_healthy { DotColor::Green } else if any_starting { DotColor::Yellow } else { DotColor::Red }
}

pub async fn snapshot(runner: &dyn CommandRunner, data_dir: &std::path::Path) -> (bool, Vec<ContainerStatus>) {
    let engine_ready = runner.run(CmdSpec::new("docker", &["info"])).await.success();
    let containers = if engine_ready { crate::compose::ps(runner, data_dir).await } else { Vec::new() };
    (engine_ready, containers)
}

pub fn setup(app: &AppHandle) -> tauri::Result<()> {
    let open_web = MenuItem::with_id(app, MENU_OPEN_WEB, "打开翻译数据库", true, None::<&str>)?;
    let open_dash = MenuItem::with_id(app, MENU_OPEN_DASHBOARD, "打开管理窗口", true, None::<&str>)?;
    let start = MenuItem::with_id(app, MENU_START, "启动", true, None::<&str>)?;
    let stop = MenuItem::with_id(app, MENU_STOP, "停止", true, None::<&str>)?;
    let restart = MenuItem::with_id(app, MENU_RESTART, "重启", true, None::<&str>)?;
    let try_docker = MenuItem::with_id(app, MENU_TRY_DOCKER, "尝试启动 Docker", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, MENU_QUIT, "退出工具（容器继续运行）", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open_web, &open_dash, &start, &stop, &restart, &try_docker, &quit])?;
    let tray = TrayIconBuilder::with_id("main")
        .icon(dot_icon(DotColor::Yellow))
        .tooltip("翻译数据库部署器")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, ev| match ev.id().as_ref() {
            MENU_OPEN_WEB => { let _ = app.emit("tray://menu", MENU_OPEN_WEB); }
            MENU_OPEN_DASHBOARD => { let _ = app.emit("tray://menu", MENU_OPEN_DASHBOARD); }
            MENU_QUIT => app.exit(0),
            other => {
                // &str 借用不能进 async spawn：先 to_string 拿所有权，spawn 内 match 用 &str
                let other = other.to_string();
                let app = app.clone();
                tauri::async_runtime::spawn(async move {
                    let runner: Arc<dyn CommandRunner> = Arc::new(crate::runner::RealRunner);
                    let dir = crate::state::data_dir().unwrap_or_default();
                    let _ = app.emit("tray://menu", other.as_str());
                    match other.as_str() {
                        MENU_TRY_DOCKER => { crate::docker::start_docker_desktop(runner.as_ref()).await; }
                        MENU_START => { crate::compose::start(runner.as_ref(), &dir).await; }
                        MENU_STOP => { crate::compose::stop(runner.as_ref(), &dir).await; }
                        MENU_RESTART => { crate::compose::restart(runner.as_ref(), &dir).await; }
                        _ => {}
                    }
                    // 动作完成立即刷一轮状态，避免最长 30s 延迟
                    let (engine_ready, containers) = snapshot(runner.as_ref(), &dir).await;
                    let color = current_color(&containers, engine_ready);
                    refresh(&app, color, engine_ready);
                    let _ = app.emit("tray://status", StatusPayload { engine_ready, containers });
                });
            }
        })
        .build(app)?;
    app.manage(TrayHandle(tray));
    Ok(())
}

pub struct TrayHandle(pub tauri::tray::TrayIcon);

pub fn refresh(app: &AppHandle, color: DotColor, engine_ready: bool) {
    if let Some(h) = app.try_state::<TrayHandle>() {
        let _ = h.0.set_icon(Some(dot_icon(color)));
        let _ = h.0.set_tooltip(Some(if engine_ready {
            "翻译数据库部署器"
        } else {
            "翻译数据库部署器（Docker 未运行）"
        }));
    }
}

/// 供测试的跃迁判定：只有「非红 → 红」边沿触发通知，并更新 prev
pub fn should_notify(prev: &mut DotColor, now: DotColor) -> bool {
    let edge = *prev != DotColor::Red && now == DotColor::Red;
    *prev = now;
    edge
}

/// `tray://status` 事件载荷，管理窗口（T5）复用
#[derive(Clone, serde::Serialize)]
pub struct StatusPayload {
    pub engine_ready: bool,
    pub containers: Vec<ContainerStatus>,
}

/// 30s 心跳：docker info + compose ps → 图标/tooltip 刷新 + `tray://status` 事件；
/// 「非红 → 红」边沿发 `tray://docker-down`。永不退出的 tokio 任务。
/// 每轮新建 runner 与 data_dir（brief 原文如此，代价可忽略）。
pub fn spawn_watcher(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let mut prev = DotColor::Yellow;
        loop {
            let runner: Arc<dyn CommandRunner> = Arc::new(crate::runner::RealRunner);
            let dir = crate::state::data_dir().unwrap_or_default();
            let (engine_ready, containers) = snapshot(runner.as_ref(), &dir).await;
            let color = current_color(&containers, engine_ready);
            refresh(&app, color, engine_ready);
            let _ = app.emit("tray://status", StatusPayload { engine_ready, containers: containers.clone() });
            if should_notify(&mut prev, color) {
                // 事件-only 通知（T3 裁定不引 tauri-plugin-notification）：前端 T5 呈现
                let _ = app.emit("tray://docker-down", ());
            }
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::compose::ContainerStatus;

    fn st(service: &str, state: &str, health: Option<&str>) -> ContainerStatus {
        ContainerStatus { service: service.into(), state: state.into(), health: health.map(String::from) }
    }

    #[test]
    fn color_is_green_only_when_all_core_healthy() {
        let all = vec![
            st("postgres", "running", Some("healthy")),
            st("elasticsearch", "running", Some("healthy")),
            st("backend", "running", Some("healthy")),
            st("frontend", "running", None),
        ];
        assert_eq!(current_color(&all, true), DotColor::Green);
    }

    #[test]
    fn color_is_yellow_when_any_health_starting() {
        let all = vec![
            st("postgres", "running", Some("healthy")),
            st("backend", "running", Some("starting")),
            st("frontend", "running", None),
        ];
        assert_eq!(current_color(&all, true), DotColor::Yellow);
    }

    #[test]
    fn color_is_red_when_exited_or_engine_down() {
        let exited = vec![st("backend", "exited", None), st("postgres", "running", Some("healthy"))];
        assert_eq!(current_color(&exited, true), DotColor::Red);
        assert_eq!(current_color(&[], false), DotColor::Red);
    }

    #[test]
    fn dot_icon_is_32x32_opaque() {
        let img = dot_icon(DotColor::Green);
        // tauri 2 的 Image 字段私有（与 brief 出入）：width/height/rgba 走访问器方法
        assert_eq!((img.width(), img.height()), (32, 32));
        assert_eq!(img.rgba().len(), 32 * 32 * 4);
    }
}

#[cfg(test)]
mod watcher_tests {
    use super::*;

    #[test]
    fn notify_only_on_fall_to_red() {
        let mut prev = DotColor::Green;
        assert!(should_notify(&mut prev, DotColor::Yellow) == false);
        assert!(should_notify(&mut prev, DotColor::Red) == true);   // 降为红 → 通知
        assert!(should_notify(&mut prev, DotColor::Red) == false);  // 保持红 → 不重复
        assert!(should_notify(&mut prev, DotColor::Green) == false);
        assert!(should_notify(&mut prev, DotColor::Red) == true);   // 再次降红 → 再通知
    }
}
