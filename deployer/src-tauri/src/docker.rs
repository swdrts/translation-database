use crate::runner::{CmdSpec, CommandRunner, RunOutput};
use serde::Serialize;
use std::path::Path;
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DockerStatus {
    pub installed: bool,
    pub engine_ready: bool,
}

pub async fn probe(runner: &dyn CommandRunner) -> DockerStatus {
    let ver = runner.run(CmdSpec::new("docker", &["--version"])).await;
    if ver.not_found() {
        return DockerStatus { installed: false, engine_ready: false };
    }
    let info = runner.run(CmdSpec::new("docker", &["info"])).await;
    DockerStatus { installed: true, engine_ready: info.success() }
}

#[cfg(windows)]
pub const DOCKER_DESKTOP_APP: &str = r"C:\Program Files\Docker\Docker\Docker Desktop.exe";
#[cfg(target_os = "macos")]
pub const DOCKER_DESKTOP_APP: &str = "/Applications/Docker.app";

/// Windows 上 Docker Desktop 可能装在自定义盘（如 D:\Program Files\Docker）——
/// 先查卸载注册表的 InstallLocation，失败再回退官方默认路径。
#[cfg(windows)]
pub fn docker_desktop_path() -> Option<std::path::PathBuf> {
    use winreg::enums::*;
    use winreg::RegKey;
    for root in [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER] {
        let Ok(key) = RegKey::predef(root)
            .open_subkey(r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Docker Desktop")
        else {
            continue;
        };
        if let Ok(loc) = key.get_value::<String, _>("InstallLocation") {
            // 实测两种布局：InstallLocation 直接是应用目录（本机 D 盘装法，
            // exe 就在 InstallLocation 下）；或指向父目录（官方默认 C: 装法，
            // exe 在嵌套的 Docker 子目录）。两个候选都试。
            for rel in [r"Docker Desktop.exe", r"Docker\Docker Desktop.exe"] {
                let p = std::path::Path::new(&loc).join(rel);
                if p.exists() {
                    return Some(p);
                }
            }
        }
    }
    let default = std::path::PathBuf::from(DOCKER_DESKTOP_APP);
    default.exists().then_some(default)
}

/// 已安装但引擎未运行时拉起 Docker Desktop。
/// Windows：Docker Desktop.exe 是常驻 GUI 进程，必须分离启动（spawn 后不等待），
/// 走 runner.run 等退出会一直阻塞到用户手动退出 Docker；macOS：`open -a Docker` 立即返回。
pub async fn start_docker_desktop(runner: &dyn CommandRunner) -> bool {
    #[cfg(windows)]
    {
        let _ = runner; // Windows 不经 runner，避免等待 GUI 进程退出
        return docker_desktop_path()
            .and_then(|p| std::process::Command::new(p).spawn().ok())
            .is_some();
    }
    #[cfg(target_os = "macos")]
    {
        return runner.run(CmdSpec::new("open", &["-a", "Docker"])).await.success();
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = runner;
        return false;
    }
}

/// 改完 daemon.json 需重启才生效：先停再启，调用方用 wait_engine 等引擎就绪
#[cfg(windows)]
pub fn stop_desktop_spec() -> CmdSpec {
    CmdSpec::new(
        "powershell",
        &["-Command", "Stop-Process -Name 'Docker Desktop','com.docker.backend' -Force -ErrorAction SilentlyContinue"],
    )
}

#[cfg(target_os = "macos")]
pub fn stop_desktop_spec() -> CmdSpec {
    CmdSpec::new("osascript", &["-e", "tell application \"Docker\" to quit"])
}

#[cfg(any(windows, target_os = "macos"))]
pub async fn restart_desktop(runner: &dyn CommandRunner) -> bool {
    let _ = runner.run(stop_desktop_spec()).await;
    // 留时间让进程彻底释放 daemon 管道，否则再启动可能检测到旧实例仍在
    tokio::time::sleep(Duration::from_secs(3)).await;
    start_docker_desktop(runner).await
}

#[cfg(not(any(windows, target_os = "macos")))]
pub async fn restart_desktop(_runner: &dyn CommandRunner) -> bool {
    false
}

/// 从引擎读回实际生效的 registry-mirrors：重启后校验与设置页展示同源。
/// `{{json .RegistryConfig.Mirrors}}` 输出 JSON 数组（无镜像时为 "null"）
pub async fn effective_mirrors(runner: &dyn CommandRunner) -> Vec<String> {
    let out = runner.run(CmdSpec::new("docker", &["info", "--format", "{{json .RegistryConfig.Mirrors}}"])).await;
    serde_json::from_str(out.stdout.trim()).unwrap_or_default()
}

pub async fn wait_engine(
    runner: &dyn CommandRunner,
    timeout: Duration,
    poll_every: Duration,
    on_wait: impl Fn(u32) + Send + Sync,
) -> bool {
    let deadline = tokio::time::Instant::now() + timeout;
    let mut tick: u32 = 0;
    loop {
        if runner.run(CmdSpec::new("docker", &["info"])).await.success() {
            return true;
        }
        if tokio::time::Instant::now() >= deadline {
            return false;
        }
        tick += 1;
        on_wait(tick);
        tokio::time::sleep(poll_every).await;
    }
}

pub fn installer_url(os: &str, arch: &str) -> String {
    match (os, arch) {
        ("windows", _) => "https://desktop.docker.com/win/main/amd64/Docker Desktop Installer.exe".into(),
        ("macos", "aarch64") => "https://desktop.docker.com/mac/main/arm64/Docker.dmg".into(),
        ("macos", _) => "https://desktop.docker.com/mac/main/amd64/Docker.dmg".into(),
        _ => unreachable!("仅支持 windows/macos"),
    }
}

pub fn parse_mount_point(hdiutil_out: &str) -> Option<String> {
    hdiutil_out
        .lines()
        .filter(|l| l.contains('\t'))
        .last()
        .and_then(|l| l.split('\t').last())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

pub fn wsl_install_spec() -> CmdSpec {
    CmdSpec::new(
        "powershell",
        &["-Command", "Start-Process wsl -ArgumentList '--install' -Verb RunAs"],
    )
}

/// Range 请求头（`bytes={len}-`）：从本地已有字节数处请求续传
pub fn range_header(existing_len: u64) -> String {
    format!("bytes={existing_len}-")
}

pub async fn download_file(
    url: &str,
    dest: &Path,
    on_progress: impl Fn(u64, u64) + Send + Sync,
) -> Result<(), String> {
    let client = reqwest::Client::new();
    // 416（Range 不可满足：本地残留字节数已超出服务器文件长度，如上次下到了错误页体
    // 或服务器端文件被截断）→ 删除残留后重试一次：重试时 existing=0、不带 Range 头，
    // 必走全新下载分支；若重试后仍异常，由下方 error_for_status 统一报错（闭环）
    //（async fn 无法直接递归（E0733 需 boxing），故用重试循环而非递归实现）
    let mut retried_416 = false;
    loop {
        let existing = tokio::fs::metadata(dest).await.map(|m| m.len()).unwrap_or(0);
        let mut req = client.get(url);
        if existing > 0 {
            req = req.header("Range", range_header(existing));
        }
        let resp = req.send().await.map_err(|e| format!("下载失败：{e}"))?;
        if resp.status().as_u16() == 416 && existing > 0 && !retried_416 {
            retried_416 = true;
            tokio::fs::remove_file(dest).await.map_err(|e| format!("清理残留下载文件失败：{e}"))?;
            continue;
        }
        // 206 → 追加模式续传（content_length 是剩余字节数，总进度需加回已有部分）；
        // 200（服务器不支持 Range 或本就全新下载）→ File::create 从头覆盖
        let resume = resp.status().as_u16() == 206;
        // HTTP 状态码校验：404/5xx 的错误页体若直接写盘，错误要到安装步远处才爆（主控授权加固）
        let resp = resp.error_for_status().map_err(|e| format!("下载失败：{e}"))?;
        let remaining = resp.content_length().unwrap_or(0);
        let total = if resume { existing + remaining } else { remaining };
        use futures_util::StreamExt;
        let mut stream = resp.bytes_stream();
        let mut file = if resume {
            tokio::fs::OpenOptions::new().append(true).open(dest).await.map_err(|e| format!("打开续传文件失败：{e}"))?
        } else {
            tokio::fs::File::create(dest).await.map_err(|e| format!("创建文件失败：{e}"))?
        };
        use tokio::io::AsyncWriteExt;
        let mut downloaded: u64 = if resume { existing } else { 0 };
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| format!("下载中断：{e}"))?;
            file.write_all(&chunk).await.map_err(|e| format!("写入失败：{e}"))?;
            downloaded += chunk.len() as u64;
            on_progress(downloaded, total);
        }
        return Ok(());
    }
}

#[cfg(windows)]
pub async fn install_windows(
    runner: &dyn CommandRunner,
    installer: &Path,
    // + 'static：run_streaming 的 on_line 是 Box<dyn Fn>（trait 对象默认 'static），
    // 包装 Box::new(on_line) 时要求本参数也为 'static（E0310，编译器建议的最小修正）。
    on_line: impl Fn(&str) + Send + Sync + 'static,
) -> RunOutput {
    runner
        .run_streaming(CmdSpec::new(installer.to_string_lossy().as_ref(), &["install", "--quiet", "--accept-license"]), Box::new(on_line))
        .await
}

#[cfg(target_os = "macos")]
pub async fn install_macos(
    runner: &dyn CommandRunner,
    dmg: &Path,
    on_line: impl Fn(&str) + Send + Sync,
) -> Result<(), String> {
    let attach = runner
        .run(CmdSpec::new("hdiutil", &["attach", "-nobrowse", "-readonly", &dmg.to_string_lossy()]))
        .await;
    if !attach.success() {
        return Err(format!("挂载 DMG 失败：{}", attach.stderr));
    }
    let mount = parse_mount_point(&attach.stdout).ok_or("无法解析挂载点")?;
    let cp = runner
        .run(CmdSpec::new("cp", &["-R", &format!("{mount}/Docker.app"), "/Applications/"]))
        .await;
    runner.run(CmdSpec::new("hdiutil", &["detach", &mount])).await;
    if !cp.success() {
        return Err(format!("拷贝 Docker.app 失败：{}", cp.stderr));
    }
    let launch = runner.run(CmdSpec::new("open", &["-a", "Docker"])).await;
    if !launch.success() {
        return Err("启动 Docker 失败，请手动打开一次 /Applications/Docker.app".into());
    }
    on_line("已在 /Applications 安装 Docker.app 并启动");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runner::{FakeRunner, RunOutput};
    use std::time::Duration;

    #[tokio::test]
    async fn probe_reports_not_installed_when_binary_missing() {
        let f = FakeRunner::default();
        f.enqueue(RunOutput { code: None, stdout: String::new(), stderr: String::new() });
        let st = probe(&f).await;
        assert_eq!(st, DockerStatus { installed: false, engine_ready: false });
        assert_eq!(f.calls_snapshot()[0].args, vec!["--version"]);
    }

    #[tokio::test]
    async fn probe_reports_installed_but_not_ready() {
        let f = FakeRunner::default();
        f.enqueue(RunOutput::ok("Docker version 27.x"));
        f.enqueue(RunOutput::fail(1, "cannot connect to the Docker daemon"));
        let st = probe(&f).await;
        assert_eq!(st, DockerStatus { installed: true, engine_ready: false });
    }

    #[tokio::test]
    async fn probe_reports_ready_when_info_ok() {
        let f = FakeRunner::default();
        f.enqueue(RunOutput::ok("Docker version 27.x"));
        f.enqueue(RunOutput::ok("Server: ..."));
        assert!(probe(&f).await.engine_ready);
    }

    #[tokio::test]
    async fn wait_engine_polls_until_success() {
        let f = FakeRunner::default();
        f.enqueue(RunOutput::fail(1, "starting"));
        f.enqueue(RunOutput::fail(1, "starting"));
        f.enqueue(RunOutput::ok("ready"));
        let ticks = std::sync::Mutex::new(0u32);
        let ok = wait_engine(&f, Duration::from_secs(30), Duration::from_millis(1), |_| {
            *ticks.lock().unwrap() += 1;
        }).await;
        assert!(ok);
        assert_eq!(*ticks.lock().unwrap(), 2);
    }

    #[test]
    fn installer_url_covers_three_platforms() {
        assert_eq!(installer_url("windows", "x86_64"), "https://desktop.docker.com/win/main/amd64/Docker Desktop Installer.exe");
        assert_eq!(installer_url("macos", "aarch64"), "https://desktop.docker.com/mac/main/arm64/Docker.dmg");
        assert_eq!(installer_url("macos", "x86_64"), "https://desktop.docker.com/mac/main/amd64/Docker.dmg");
    }

    #[test]
    fn parse_mount_point_finds_docker_mount() {
        // 挂载行使用真实 hdiutil attach 输出的 tab 分隔（brief 该行误写为空格，与实现和 None 断言矛盾）
        let out = "expected   CRC-32 ...\n/dev/disk4s1\tApple_HFS\t/Volumes/Docker\n";
        assert_eq!(parse_mount_point(out).as_deref(), Some("/Volumes/Docker"));
        assert_eq!(parse_mount_point("no tab-separated mount here"), None);
    }

    #[test]
    fn wsl_install_spec_uses_elevated_powershell() {
        let spec = wsl_install_spec();
        assert_eq!(spec.program, "powershell");
        assert!(spec.args.join(" ").contains("wsl"));
        assert!(spec.args.join(" ").contains("RunAs"));
    }

    #[test]
    fn range_header_resumes_from_existing_length() {
        assert_eq!(range_header(1024), "bytes=1024-");
        assert_eq!(range_header(0), "bytes=0-");
    }

    #[tokio::test]
    async fn effective_mirrors_parses_docker_info_json() {
        let f = FakeRunner::default();
        f.enqueue(RunOutput::ok("[\"https://docker.1ms.run\"]"));
        assert_eq!(effective_mirrors(&f).await, vec!["https://docker.1ms.run".to_string()]);
        // 无镜像时引擎输出 "null"，应解析为空而非报错
        let f = FakeRunner::default();
        f.enqueue(RunOutput::ok("null"));
        assert!(effective_mirrors(&f).await.is_empty());
    }

    #[test]
    #[cfg(any(windows, target_os = "macos"))]
    fn stop_desktop_spec_targets_docker_processes() {
        let spec = stop_desktop_spec();
        assert!(spec.args.join(" ").contains("Docker"));
    }
}
