mod commands;
mod compose;
mod config;
mod docker;
mod runner;
mod state;

use commands::AppState;
use runner::RealRunner;
use std::sync::{Arc, Mutex};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let data_dir = state::data_dir().expect("无法创建数据目录");
    let persisted = state::load(&data_dir);
    tauri::Builder::default()
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
            commands::reset_state
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
