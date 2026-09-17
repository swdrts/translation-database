mod commands;
mod compose;
mod config;
mod docker;
mod runner;
mod state;
mod tray;

use commands::AppState;
use runner::RealRunner;
use state::PersistedState;
use std::sync::{Arc, Mutex};
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let data_dir = state::data_dir().expect("无法创建数据目录");
    // 启动期加载失败不 panic（如权限被拒）：以初始态兜底启动，具体错误场景由 get_app_state 上报前端
    let persisted = state::load(&data_dir).unwrap_or_else(|_| PersistedState::initial());
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            // 二次启动：聚焦既有主窗口而不是新起进程
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }))
        .plugin(tauri_plugin_opener::init())
        .manage(AppState {
            runner: Arc::new(RealRunner),
            data_dir,
            state: Mutex::new(persisted),
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_app_state,
            commands::check_env,
            commands::docker_probe,
            commands::ensure_docker,
            commands::install_wsl2,
            commands::save_config,
            commands::start_deploy,
            commands::open_web,
            commands::reset_state,
            commands::refresh_status,
            commands::stack_op,
            commands::open_dashboard_window,
            commands::web_url
        ])
        .setup(|app| {
            tray::setup(app.handle())?;
            #[cfg(target_os = "macos")]
            let _ = app.set_activation_policy(tauri::ActivationPolicy::Accessory); // 无 Dock 图标的托盘应用
            let main = app.get_webview_window("main").unwrap();
            let h = app.handle().clone();
            main.on_window_event(move |ev| {
                if let tauri::WindowEvent::CloseRequested { api, .. } = ev {
                    api.prevent_close();          // 关闭即驻托盘
                    let _ = h.get_webview_window("main").map(|w| w.hide());
                }
            });
            // 管理窗口同样关闭驻托盘：若任其销毁，后续 open_dashboard_window 的
            // get_webview_window("dashboard") 将永远拿不到实例，托盘菜单入口失效
            let dash = app.get_webview_window("dashboard").unwrap();
            let dh = app.handle().clone();
            dash.on_window_event(move |ev| {
                if let tauri::WindowEvent::CloseRequested { api, .. } = ev {
                    api.prevent_close();
                    let _ = dh.get_webview_window("dashboard").map(|w| w.hide());
                }
            });
            tray::spawn_watcher(app.handle().clone());
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
