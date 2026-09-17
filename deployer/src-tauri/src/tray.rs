use crate::compose::ContainerStatus;
use crate::runner::CmdSpec;
use crate::runner::CommandRunner;
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
            other => { let _ = app.emit("tray://menu", other); }
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
