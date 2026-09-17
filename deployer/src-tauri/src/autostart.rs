//! 三层自启中的 Docker Desktop 层（另两层：工具自身走 tauri-plugin-autostart，
//! 容器随引擎自启走 compose restart 策略，均在别处接线）。

/// Windows Run 键值格式：路径含空格必须整体加引号，-Autostart 让 Docker Desktop
/// 登录后以自启模式启动（不弹首屏窗口）。
pub fn render_run_value(path: &str) -> String {
    format!("\"{path}\" -Autostart")
}

/// Docker Desktop 4.x 的 openAtLogin 开关存于 settings-store.json。
/// 文件缺失/损坏时全新创建 `{"openAtLogin":…}`；serde_json 读写保住其余键。
pub fn set_macos_open_at_login(file: &std::path::Path, on: bool) -> std::io::Result<()> {
    let mut v: serde_json::Value = std::fs::read_to_string(file)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| serde_json::json!({}));
    v["openAtLogin"] = serde_json::Value::Bool(on);
    // 紧凑序列化（非 to_vec_pretty）：Docker Desktop 自身即以单行紧凑 JSON 写此文件，
    // 且测试断言的正是紧凑形态 `"openAtLogin":true`（pretty 会在冒号后多出空格）
    std::fs::write(file, serde_json::to_vec(&v)?)
}

/// 登录时拉起 Docker Desktop（引擎就绪后容器由 restart 策略自动恢复，实现整栈开机自启）。
/// 统一 async：macOS 的 osascript fallback 需 await runner.run；Windows 分支无 await 亦兼容。
#[cfg(windows)]
pub async fn set_docker_autostart(runner: &dyn crate::runner::CommandRunner, on: bool) -> Result<(), String> {
    let _ = runner; // Windows 直接写注册表，不经 runner
    use winreg::enums::*;
    use winreg::RegKey;
    let key = RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", KEY_SET_VALUE)
        .map_err(|e| format!("打开 Run 注册表键失败：{e}"))?;
    if on {
        let exe = crate::docker::docker_desktop_path().ok_or("未找到 Docker Desktop")?;
        key.set_value("Docker Desktop", &render_run_value(&exe.to_string_lossy()))
            .map_err(|e| format!("写入自启失败：{e}"))
    } else {
        // 关自启为幂等删除：值本就不存在时不算失败
        let _ = key.delete_value("Docker Desktop");
        Ok(())
    }
}

#[cfg(target_os = "macos")]
pub async fn set_docker_autostart(runner: &dyn crate::runner::CommandRunner, on: bool) -> Result<(), String> {
    let file = dirs::home_dir().ok_or("无 home 目录")?
        .join("Library/Group Containers/group.com.docker/settings-store.json");
    if set_macos_open_at_login(&file, on).is_ok() {
        return Ok(());
    }
    // settings-store.json 不可写（如 Docker 从未运行、容器目录不存在）时回退系统登录项
    let script = if on {
        r#"tell application "System Events" to make login item at end with properties {path:"/Applications/Docker.app", hidden:false}"#
    } else {
        r#"tell application "System Events" to delete login item "Docker""#
    };
    let out = runner.run(crate::runner::CmdSpec::new("osascript", &["-e", script])).await;
    if out.success() { Ok(()) } else { Err(format!("设置登录项失败：{}", out.stderr.trim())) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn run_value_quotes_path_and_appends_autostart() {
        assert_eq!(
            render_run_value(r"D:\Program Files\Docker\Docker Desktop.exe"),
            r#""D:\Program Files\Docker\Docker Desktop.exe" -Autostart"#
        );
    }

    #[test]
    fn toggling_macos_settings_json_open_at_login() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("settings-store.json");
        std::fs::write(&f, r#"{"openAiKey":null,"openAtLogin":false}"#).unwrap();
        set_macos_open_at_login(&f, true).unwrap();
        let raw = std::fs::read_to_string(&f).unwrap();
        assert!(raw.contains(r#""openAtLogin":true"#) && !raw.contains("false"));
        set_macos_open_at_login(&f, false).unwrap();
        assert!(std::fs::read_to_string(&f).unwrap().contains(r#""openAtLogin":false"#));
    }
}
