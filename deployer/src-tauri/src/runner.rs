use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::Mutex;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CmdSpec {
    pub program: String,
    pub args: Vec<String>,
}

impl CmdSpec {
    pub fn new(program: &str, args: &[&str]) -> Self {
        Self { program: program.into(), args: args.iter().map(|s| s.to_string()).collect() }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct RunOutput {
    pub code: Option<i32>,
    pub stdout: String,
    pub stderr: String,
}

impl RunOutput {
    pub fn success(&self) -> bool { self.code == Some(0) }
    pub fn not_found(&self) -> bool { self.code.is_none() }
    pub fn ok(stdout: &str) -> Self { Self { code: Some(0), stdout: stdout.into(), stderr: String::new() } }
    pub fn fail(code: i32, stderr: &str) -> Self { Self { code: Some(code), stdout: String::new(), stderr: stderr.into() } }
}

#[async_trait]
pub trait CommandRunner: Send + Sync {
    async fn run(&self, spec: CmdSpec) -> RunOutput;
    async fn run_streaming<F>(&self, spec: CmdSpec, on_line: F) -> RunOutput
    where
        F: Fn(&str) + Send + Sync;
}

pub struct RealRunner;

fn base_command(spec: &CmdSpec) -> Command {
    let mut cmd = Command::new(&spec.program);
    cmd.args(&spec.args);
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

#[async_trait]
impl CommandRunner for RealRunner {
    async fn run(&self, spec: CmdSpec) -> RunOutput {
        match base_command(&spec).output().await {
            Ok(out) => RunOutput {
                code: out.status.code(),
                stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
                stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
            },
            Err(_) => RunOutput { code: None, stdout: String::new(), stderr: String::new() },
        }
    }

    async fn run_streaming<F>(&self, spec: CmdSpec, on_line: F) -> RunOutput
    where
        F: Fn(&str) + Send + Sync,
    {
        let mut cmd = base_command(&spec);
        cmd.stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped());
        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(_) => return RunOutput { code: None, stdout: String::new(), stderr: String::new() },
        };
        let mut stdout_lines = Vec::new();
        if let Some(stdout) = child.stdout.take() {
            let mut reader = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = reader.next_line().await {
                on_line(&line);
                stdout_lines.push(line);
            }
        }
        let output = match child.wait_with_output().await {
            Ok(o) => o,
            Err(_) => return RunOutput { code: None, stdout: stdout_lines.join("\n"), stderr: String::new() },
        };
        RunOutput {
            code: output.status.code(),
            stdout: stdout_lines.join("\n"),
            stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
        }
    }
}

#[derive(Default)]
pub struct FakeRunner {
    pub calls: Mutex<Vec<CmdSpec>>,
    pub responses: Mutex<VecDeque<RunOutput>>,
}

impl FakeRunner {
    pub fn enqueue(&self, out: RunOutput) { self.responses.lock().unwrap().push_back(out); }
    pub fn calls_snapshot(&self) -> Vec<CmdSpec> { self.calls.lock().unwrap().clone() }
}

#[async_trait]
impl CommandRunner for FakeRunner {
    async fn run(&self, spec: CmdSpec) -> RunOutput {
        self.calls.lock().unwrap().push(spec);
        self.responses.lock().unwrap().pop_front().unwrap_or(RunOutput::ok(""))
    }

    async fn run_streaming<F>(&self, spec: CmdSpec, on_line: F) -> RunOutput
    where
        F: Fn(&str) + Send + Sync,
    {
        let out = self.run(spec).await;
        for line in out.stdout.lines() {
            on_line(line);
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn fake_runner_records_calls_and_pops_responses() {
        let fake = FakeRunner::default();
        fake.enqueue(RunOutput { code: Some(0), stdout: "ok".into(), stderr: String::new() });
        let out = fake.run(CmdSpec { program: "docker".into(), args: vec!["info".into()] }).await;
        assert!(out.success());
        assert_eq!(fake.calls_snapshot(), vec![CmdSpec { program: "docker".into(), args: vec!["info".into()] }]);
    }

    #[tokio::test]
    async fn fake_runner_streaming_emits_each_stdout_line() {
        let fake = FakeRunner::default();
        fake.enqueue(RunOutput { code: Some(0), stdout: "line1\nline2\n".into(), stderr: String::new() });
        let seen = std::sync::Mutex::new(Vec::new());
        fake.run_streaming(CmdSpec { program: "x".into(), args: vec![] }, |l| seen.lock().unwrap().push(l.to_string())).await;
        assert_eq!(*seen.lock().unwrap(), vec!["line1".to_string(), "line2".to_string()]);
    }

    #[tokio::test]
    async fn real_runner_runs_echo() {
        let real = RealRunner;
        let out = real.run(CmdSpec { program: "cmd".into(), args: vec!["/C".into(), "echo hello".into()] }).await;
        assert!(out.success());
        assert_eq!(out.stdout.trim(), "hello");
    }
}
