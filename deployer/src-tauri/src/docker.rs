use crate::runner::{CmdSpec, CommandRunner};
use serde::Serialize;
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

/// 已安装但引擎未运行时拉起 Docker Desktop。
/// Windows：Docker Desktop.exe 是常驻 GUI 进程，必须分离启动（spawn 后不等待），
/// 走 runner.run 等退出会一直阻塞到用户手动退出 Docker；macOS：`open -a Docker` 立即返回。
pub async fn start_docker_desktop(runner: &dyn CommandRunner) -> bool {
    #[cfg(windows)]
    {
        let _ = runner; // Windows 不经 runner，避免等待 GUI 进程退出
        return std::process::Command::new(DOCKER_DESKTOP_APP).spawn().is_ok();
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
}
