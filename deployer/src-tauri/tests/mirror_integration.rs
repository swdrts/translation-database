//! 真实 Docker 引擎集成测试（非单测）：前置条件为本机 Docker Desktop 已启动。
//! 运行：cargo test --test mirror_integration -- --ignored --nocapture
//! 全链路：写入实测可用的国内默认镜像（向导/设置页同一函数）→ 重启引擎 →
//! 从 `docker info` 回读生效镜像并断言一致 → 删本地缓存后真实 `docker pull`
//! 验证端到端拉取 → 无论成败恢复用户原配置并再次重启使其重新生效。

use deployer_lib::docker;
use deployer_lib::registry;
use deployer_lib::runner::{CmdSpec, CommandRunner, RealRunner};
use std::path::Path;
use std::time::Duration;

/// 引擎上报的镜像会补尾斜杠（如 "https://docker.1ms.run/"），比较前统一剥掉
fn normalize(m: &str) -> String {
    m.trim_end_matches('/').to_string()
}

async fn flow(runner: &RealRunner, path: &Path) -> Result<(), String> {
    let mirrors: Vec<String> = registry::DEFAULT_MIRRORS.iter().map(|s| s.to_string()).collect();
    registry::write_mirrors(path, &mirrors).map_err(|e| format!("写入 daemon.json 失败：{e}"))?;
    println!("已写入默认镜像：{mirrors:?}");

    if !docker::restart_desktop(runner).await {
        return Err("重启 Docker Desktop 失败".into());
    }
    let ready = docker::wait_engine(runner, Duration::from_secs(240), Duration::from_secs(3), |t| {
        println!("等待引擎重启就绪…{} 秒", t * 3);
    }).await;
    if !ready {
        return Err("重启后引擎 240 秒内未就绪".into());
    }

    let effective = docker::effective_mirrors(runner).await;
    println!("引擎实际生效镜像：{effective:?}");
    let effective_norm: Vec<String> = effective.iter().map(|m| normalize(m)).collect();
    if effective_norm != mirrors {
        return Err(format!("生效镜像与写入不一致：{effective_norm:?}"));
    }

    // 删本地缓存强制走网络，验证镜像加速下端到端拉取成功
    let _ = runner.run(CmdSpec::new("docker", &["rmi", "hello-world:latest"])).await;
    let pull = runner.run(CmdSpec::new("docker", &["pull", "hello-world:latest"])).await;
    println!("pull stdout: {}", pull.stdout.trim());
    if !pull.success() {
        return Err(format!("镜像拉取失败：{}", pull.stderr.trim()));
    }
    Ok(())
}

#[tokio::test]
#[ignore]
async fn real_engine_applies_default_mirrors_and_pull_succeeds() {
    let runner = RealRunner;
    assert!(docker::probe(&runner).await.engine_ready, "前置条件：Docker 引擎必须已就绪");

    let path = registry::daemon_json_path();
    let backup = std::fs::read_to_string(&path).ok();

    let result = flow(&runner, &path).await;

    // 无论成败都恢复用户原配置，并重启引擎使其重新生效
    match &backup {
        Some(b) => std::fs::write(&path, b).expect("恢复 daemon.json 备份失败"),
        None => { let _ = std::fs::remove_file(&path); }
    }
    assert!(docker::restart_desktop(&runner).await, "恢复后重启 Docker Desktop 失败");
    assert!(
        docker::wait_engine(&runner, Duration::from_secs(240), Duration::from_secs(3), |_| {}).await,
        "恢复后引擎未就绪",
    );
    println!("已恢复用户原镜像配置并重启生效");

    result.expect("集成测试失败");
}