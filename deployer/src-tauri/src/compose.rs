use crate::runner::{CmdSpec, CommandRunner, RunOutput};
use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};

pub const PROJECT_NAME: &str = "transdb";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ContainerStatus {
    pub service: String,
    pub state: String,
    pub health: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct PullEvent {
    pub service: String,
    pub phase: String,
    pub detail: String,
}

pub fn base_args(project_dir: &Path) -> Vec<String> {
    vec![
        "compose".into(),
        "-p".into(),
        PROJECT_NAME.into(),
        "--project-directory".into(),
        project_dir.to_string_lossy().into_owned(),
    ]
}

fn spec(project_dir: &Path, extra: &[&str]) -> CmdSpec {
    let mut args = base_args(project_dir);
    args.extend(extra.iter().map(|s| s.to_string()));
    CmdSpec { program: "docker".into(), args }
}

pub fn materialize_project(data_dir: &Path, compose_yml: &str, env_content: &str) -> io::Result<PathBuf> {
    std::fs::write(data_dir.join("docker-compose.yml"), compose_yml)?;
    std::fs::write(data_dir.join(".env"), env_content)?;
    Ok(data_dir.to_path_buf())
}

// on_line 需 'static：coerce 到 runner::run_streaming 的 Box<dyn Fn + Send + Sync>（默认 'static）；
// 调用方（Task 9）均以 move 闭包传入，不受影响。brief 原文无此 bound，系编译器要求的修正。
// 不加 --progress=plain：它不是 pull 子命令的合法标志（Docker Desktop 27.5.1 实测报 unknown flag）；
// 子进程 stdout 为管道时 compose 自动输出 plain 进度行，parse_pull_line 解析的正是这种格式。
pub async fn pull(runner: &dyn CommandRunner, dir: &Path, on_line: impl Fn(&str) + Send + Sync + 'static) -> RunOutput {
    runner.run_streaming(spec(dir, &["pull"]), Box::new(on_line)).await
}

pub async fn up_wait(runner: &dyn CommandRunner, dir: &Path, on_line: impl Fn(&str) + Send + Sync + 'static) -> RunOutput {
    runner.run_streaming(spec(dir, &["up", "-d", "--wait"]), Box::new(on_line)).await
}

pub async fn stop(runner: &dyn CommandRunner, dir: &Path) -> RunOutput {
    runner.run(spec(dir, &["stop"])).await
}

pub async fn start(runner: &dyn CommandRunner, dir: &Path) -> RunOutput {
    runner.run(spec(dir, &["start"])).await
}

pub async fn restart(runner: &dyn CommandRunner, dir: &Path) -> RunOutput {
    runner.run(spec(dir, &["restart"])).await
}

pub async fn down(runner: &dyn CommandRunner, dir: &Path, remove_volumes: bool) -> RunOutput {
    let extra = if remove_volumes { &["down", "-v"][..] } else { &["down"][..] };
    runner.run(spec(dir, extra)).await
}

/// 返回完整 RunOutput 而非 String：失败（引擎中途停掉、服务名不存在等）时 stdout 为空，
/// 调用方若只取 stdout 会把失败吞成「空日志」；由 commands::container_logs 判定后上抛
pub async fn logs(runner: &dyn CommandRunner, dir: &Path, service: &str) -> RunOutput {
    runner.run(spec(dir, &["logs", "--no-color", "--tail", "500", service])).await
}

pub async fn ps(runner: &dyn CommandRunner, dir: &Path) -> Vec<ContainerStatus> {
    let out = runner.run(spec(dir, &["ps", "--all", "--format", "json"])).await;
    parse_ps_output(&out.stdout)
}

#[derive(Deserialize)]
// rename_all：docker compose ps --format json 输出 PascalCase 键（Service/State/Health），
// brief 原文缺此属性导致字段全部命中 #[serde(default)] 变成空串，故补上。
#[serde(rename_all = "PascalCase")]
struct RawPs {
    #[serde(default)]
    pub service: String,
    #[serde(default)]
    pub state: String,
    pub health: Option<String>,
}

pub fn parse_ps_output(raw: &str) -> Vec<ContainerStatus> {
    let raw = raw.trim();
    if raw.is_empty() {
        return Vec::new();
    }
    let parsed: Option<Vec<RawPs>> = if raw.starts_with('[') {
        serde_json::from_str(raw).ok()
    } else {
        // 逐行解析、坏行跳过：docker 输出中混入半行日志不应清空整个状态面板
        Some(
            raw.lines()
                .filter_map(|l| serde_json::from_str::<RawPs>(l).ok())
                .collect(),
        )
    };
    parsed
        .unwrap_or_default()
        .into_iter()
        .map(|r| ContainerStatus { service: r.service, state: r.state, health: r.health })
        .collect()
}

pub fn parse_pull_line(line: &str) -> Option<PullEvent> {
    let (service, rest) = line.split_once(' ')?;
    // 首字符预过滤：P=Pulling/Pulled、V=Verifying、E=Extracting/Error、D=Downloading/Downloaded。
    // （brief 原文为元组 ('P','V','E')，但 char 元组未实现 Pattern 无法编译，
    // 且缺少 'D' 会漏掉 Downloading/Downloaded，故用数组并补 'D'。）
    if service.is_empty() || !rest.starts_with(['P', 'V', 'E', 'D']) {
        return None;
    }
    let mut parts = rest.split_whitespace();
    let phase = parts.next().unwrap_or_default();
    if !matches!(phase, "Pulling" | "Pulled" | "Downloading" | "Extracting" | "Verifying" | "Downloaded" | "Error") {
        return None;
    }
    // detail 取最后一个 token（如 "Downloading [===>  ]  12.5MB/85.4MB" → "12.5MB/85.4MB"）
    let detail = parts.last().unwrap_or_default().to_string();
    Some(PullEvent { service: service.into(), phase: phase.into(), detail })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_ps_accepts_json_array() {
        let raw = r#"[{"Service":"frontend","State":"running","Health":"healthy"},{"Service":"postgres","State":"running","Health":null}]"#;
        let ps = parse_ps_output(raw);
        assert_eq!(ps.len(), 2);
        assert_eq!(ps[0], ContainerStatus { service: "frontend".into(), state: "running".into(), health: Some("healthy".into()) });
        assert_eq!(ps[1].health, None);
    }

    #[test]
    fn parse_ps_accepts_json_lines() {
        let raw = "{\"Service\":\"backend\",\"State\":\"running\",\"Health\":\"starting\"}\n{\"Service\":\"elasticsearch\",\"State\":\"exited\",\"Health\":null}\n";
        let ps = parse_ps_output(raw);
        assert_eq!(ps.len(), 2);
        assert_eq!(ps[0].service, "backend");
        assert_eq!(ps[1].state, "exited");
    }

    #[test]
    fn parse_ps_tolerates_garbage() {
        assert!(parse_ps_output("").is_empty());
        assert!(parse_ps_output("Error response from daemon").is_empty());
    }

    #[test]
    fn parse_ps_jsonl_skips_bad_line_but_keeps_good_ones() {
        let raw = "{\"Service\":\"backend\",\"State\":\"running\"}\n不是JSON\n{\"Service\":\"postgres\",\"State\":\"running\"}\n";
        let ps = parse_ps_output(raw);
        assert_eq!(ps.len(), 2);
        assert_eq!(ps[0].service, "backend");
        assert_eq!(ps[1].service, "postgres");
    }

    #[test]
    fn parse_pull_line_matches_compose_plain_progress() {
        assert_eq!(parse_pull_line("backend Pulling"), Some(PullEvent { service: "backend".into(), phase: "Pulling".into(), detail: String::new() }));
        assert_eq!(
            parse_pull_line("postgres Downloading [===>                ]  12.5MB/85.4MB"),
            Some(PullEvent { service: "postgres".into(), phase: "Downloading".into(), detail: "12.5MB/85.4MB".into() })
        );
        assert_eq!(parse_pull_line("backend Pulled"), Some(PullEvent { service: "backend".into(), phase: "Pulled".into(), detail: String::new() }));
        assert_eq!(parse_pull_line("3be7c8e5e8f1 Pull complete"), None);
    }

    #[tokio::test]
    async fn materialize_writes_compose_and_env() {
        let dir = tempfile::tempdir().unwrap();
        let project = materialize_project(dir.path(), "services: {}\n", "TRANSDB_FRONTEND_PORT=80\n").unwrap();
        assert_eq!(project, dir.path().to_path_buf());
        assert_eq!(std::fs::read_to_string(dir.path().join("docker-compose.yml")).unwrap(), "services: {}\n");
        assert_eq!(std::fs::read_to_string(dir.path().join(".env")).unwrap(), "TRANSDB_FRONTEND_PORT=80\n");
    }
}
