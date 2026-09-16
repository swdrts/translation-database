# 一键部署器·计划 1：部署核心与向导（transdb-deployer core & wizard）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建能真实完成一次部署的 Tauri 2 桌面工具：根 compose 改造、子进程抽象、配置生成、断点状态机、Docker 检测/安装/等待、compose 封装、向导五步 UI。

**Architecture:** 工具是壳、compose 是核——Rust 侧经 `CommandRunner` trait 子进程调用 `docker compose -p transdb`，向导 UI（Vue 3）通过 Tauri command 驱动状态机，进度经 Tauri event 流式回传。托盘/管理窗口/CI 属计划 2。

**Tech Stack:** Tauri 2 + Rust（tokio/reqwest/rand/sysinfo/dirs）、Vue 3 + TypeScript + Element Plus + Vite 6 + Vitest 2（与 `frontend/` 同栈）。

**设计规格：** `docs/superpowers/specs/2026-09-16-oneclick-deployer-design.md`

## Global Constraints

- **git 提交规则（用户全局硬性规则）**：每个 Commit 步骤一律改为「准备提交——停下向用户展示要提交的文件与信息，用户明确同意后才执行」；派发 subagent 的 prompt **绝不包含** git add/commit/push 等指令，提交由主控统一执行。
- Rust ≥ 1.77.2（Tauri 2 要求）；Node ≥ 18；npm 为包管理器（与 frontend 一致，不引入 pnpm/yarn）。
- 前端栈与 `frontend/` 完全一致：TypeScript ~5.6、Vue ^3.5、element-plus ^2.9、Vite ^6、Vitest ^2、`vue-tsc -b` 构建。
- compose 项目名固定 `transdb`；数据目录：Windows `%APPDATA%\transdb\`、macOS `~/Library/Application Support/transdb/`（用 `dirs::data_dir()`）。
- 默认值：端口 80、ES 堆 2GB、后端堆 1GB、镜像版本 `0.1.0`；admin 密码最短 8 位；JWT 密钥 48 随机字节 hex、DB 密码 24 位字母数字。
- UI 文案全部中文；`docker compose` 子命令一律带 `-p transdb --project-directory <数据目录>`。
- Rust 单元测试不依赖真实 Docker（经 FakeRunner 注入）；每个 `cargo test` / `npm test` 必须通过后才进入下一任务。
- 验证命令均在仓库根的 Git Bash 中执行。

---

### Task 1: 根 docker-compose.yml 改造（restart 策略 + 镜像版本参数化）

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: compose 接受环境变量 `TRANSDB_APP_VERSION`（缺省 `0.1.0`）决定 backend/frontend 镜像 tag；4 个服务均有 `restart: unless-stopped`。Task 11 的资源同步与计划 2 的升级功能依赖此参数化。

- [ ] **Step 1: 修改 docker-compose.yml**

4 个服务（postgres、elasticsearch、backend、frontend）各加一行 `restart: unless-stopped`（放在 `healthcheck:`/`ports:` 同级）；backend 与 frontend 的镜像行改为参数化：

```yaml
  backend:
    image: swdrts/transdb-backend:${TRANSDB_APP_VERSION:-0.1.0}
    restart: unless-stopped
    # environment 及以下保持不变

  frontend:
    image: swdrts/transdb-frontend:${TRANSDB_APP_VERSION:-0.1.0}
    restart: unless-stopped
    ports:
      - "${TRANSDB_FRONTEND_PORT:-80}:80"
```

postgres、elasticsearch 两个服务的 `image:` 行不动，只加 `restart: unless-stopped`。

- [ ] **Step 2: .env.example 增补可选项注释**

在「可选」注释块末尾追加一行：

```
# TRANSDB_APP_VERSION=0.1.0
```

- [ ] **Step 3: 验证 compose 语法与默认值解析**

Run: `docker compose config | grep -E "image:|restart:"`
Expected: 恰好 4 行 `restart: unless-stopped`；backend/frontend 的 image 行解析为 `swdrts/transdb-backend:0.1.0` / `swdrts/transdb-frontend:0.1.0`（根目录无 `TRANSDB_APP_VERSION` 时取默认）。

再 Run: `TRANSDB_APP_VERSION=9.9.9 docker compose config | grep "image:"`
Expected: 两个业务镜像解析为 `:9.9.9`，证明参数化生效。

- [ ] **Step 4: 准备提交（需用户确认）**

向用户展示改动（`git diff docker-compose.yml .env.example`）与提交信息，同意后执行：

```bash
git add docker-compose.yml .env.example
git commit -m "feat: compose 全服务 restart 策略与镜像版本参数化（一键部署器前置改造）"
```

---

### Task 2: deployer/ Tauri 2 + Vue 3 脚手架

**Files:**
- Create: `deployer/`（脚手架生成后整体调整：`package.json`、`vite.config.ts`、`tsconfig.json`、`index.html`、`src/main.ts`、`src/App.vue`、`src-tauri/tauri.conf.json`、`src-tauri/Cargo.toml`、`src-tauri/src/main.rs`、`src-tauri/src/lib.rs`、`src-tauri/capabilities/default.json` 等）
- Modify: `.gitignore`
- Test: `deployer/src/App.test.ts`

**Interfaces:**
- Consumes: 无
- Produces: 可运行的 Tauri 应用骨架——`deployer_lib::run()`（lib.rs）注册 command 的入口函数；窗口标题「翻译数据库部署器」；前端 `npm test` / Rust `cargo test` 基线可用；`src-tauri` crate 名 `deployer`（lib 名 `deployer_lib`）。

- [ ] **Step 1: 官方模板生成脚手架**

Run: `npm create tauri-app@latest deployer -- --template vue-ts --manager npm --yes`
（在仓库根执行；生成的目录含默认图标与 capabilities，可直接构建。）

- [ ] **Step 2: 调整 package.json**

```json
{
  "name": "transdb-deployer",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev:front": "vite",
    "build:front": "vue-tsc -b && vite build",
    "tauri": "tauri",
    "test": "vitest run"
  },
  "dependencies": {
    "@tauri-apps/api": "^2",
    "element-plus": "^2.9.3",
    "vue": "^3.5.13"
  },
  "devDependencies": {
    "@tauri-apps/cli": "^2",
    "@vitejs/plugin-vue": "^5.2.1",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1",
    "typescript": "~5.6.3",
    "vite": "^6.0.7",
    "vitest": "^2.1.8",
    "vue-tsc": "^2.2.0"
  }
}
```

Run: `cd deployer && npm install`

- [ ] **Step 3: 写第一个前端测试（先失败）**

`deployer/src/App.test.ts`：

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

describe('App', () => {
  it('渲染部署器标题', () => {
    const wrapper = mount(App, { global: { plugins: [] } })
    expect(wrapper.text()).toContain('翻译数据库部署器')
  })
})
```

`deployer/src/App.vue`（模板默认内容先不动）：

```vue
<script setup lang="ts"></script>

<template>
  <main style="padding: 24px">
    <h1>翻译数据库部署器</h1>
  </main>
</template>
```

`deployer/vitest.config.ts`（对齐 frontend 惯例）：

```typescript
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: { environment: 'jsdom' },
})
```

`deployer/vite.config.ts`：

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  clearScreen: false,
  server: { port: 1420, strictPort: true },
  build: { target: 'chrome105', sourcemap: true },
})
```

`deployer/src/main.ts`：

```typescript
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd deployer && npm test`
Expected: PASS（1 个用例）。若脚手架自带示例测试导致失败，删除示例测试文件。

- [ ] **Step 5: 调整 tauri.conf.json 与 Rust 入口**

`deployer/src-tauri/tauri.conf.json` 关键字段（其余保留模板值）：

```json
{
  "productName": "transdb-deployer",
  "version": "0.1.0",
  "identifier": "app.transdb.deployer",
  "build": {
    "beforeDevCommand": "npm run dev:front",
    "devUrl": "http://localhost:1420",
    "beforeBuildCommand": "npm run build:front",
    "frontendDist": "../dist"
  },
  "app": {
    "windows": [
      { "title": "翻译数据库部署器", "width": 880, "height": 640, "resizable": true }
    ],
    "security": { "csp": null }
  },
  "bundle": { "active": true, "targets": "all", "icon": ["icons/32x32.png", "icons/128x128.png", "icons/128x128@2x.png", "icons/icon.icns", "icons/icon.ico"] }
}
```

`deployer/src-tauri/Cargo.toml` 里 `[lib] name = "deployer_lib"`，`[[bin]] name = "deployer"`（模板默认即如此，确认即可）。

`deployer/src-tauri/src/lib.rs`：

```rust
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

`deployer/src-tauri/src/main.rs`：

```rust
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    deployer_lib::run()
}
```

- [ ] **Step 6: 验证开发模式跑通 + Rust 测试基线**

Run: `cd deployer && npm run tauri dev`（人工确认：窗口打开、标题正确、显示「翻译数据库部署器」标题文字；Ctrl+C 退出）
Run: `cd deployer/src-tauri && cargo test`
Expected: `0 passed` 或模板自带测试通过，无编译错误。

- [ ] **Step 7: 更新 .gitignore 并纳入新目录**

仓库根 `.gitignore` 追加：

```
deployer/node_modules/
deployer/dist/
deployer/src-tauri/target/
deployer/src-tauri/gen/
```

Run: `git status --short` 确认 node_modules/target 未被跟踪。

- [ ] **Step 8: 准备提交（需用户确认）**

```bash
git add deployer .gitignore
git commit -m "feat: deployer Tauri 2 + Vue 3 + Element Plus 脚手架"
```

---

### Task 3: runner.rs 子进程抽象（真实现 + 假实现）

**Files:**
- Create: `deployer/src-tauri/src/runner.rs`
- Modify: `deployer/src-tauri/Cargo.toml`（补全依赖）、`deployer/src-tauri/src/lib.rs`（声明模块）
- Test: `deployer/src-tauri/src/runner.rs`（内联 #[cfg(test)]）

**Interfaces:**
- Consumes: 无
- Produces（后续所有任务依赖）：

```rust
pub struct CmdSpec { pub program: String, pub args: Vec<String> }
pub struct RunOutput { pub code: Option<i32>, pub stdout: String, pub stderr: String } // code=None 表示进程启动失败（如程序不存在）
impl RunOutput { pub fn success(&self) -> bool; pub fn not_found(&self) -> bool; }
#[async_trait] pub trait CommandRunner: Send + Sync {
    async fn run(&self, spec: CmdSpec) -> RunOutput;
    async fn run_streaming<F>(&self, spec: CmdSpec, on_line: F) -> RunOutput where F: Fn(&str) + Send + Sync;
}
pub struct RealRunner;
#[derive(Default)] pub struct FakeRunner { pub calls: Mutex<Vec<CmdSpec>>, pub responses: Mutex<VecDeque<RunOutput>> }
impl FakeRunner { pub fn enqueue(&self, out: RunOutput); pub fn calls_snapshot(&self) -> Vec<CmdSpec>; }
```

- [ ] **Step 1: Cargo.toml 一次性补全依赖**

```toml
[dependencies]
tauri = { version = "2", features = [] }
tauri-plugin-opener = "2"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["process", "io-util", "time", "macros", "rt"] }
async-trait = "0.1"
rand = "0.8"
reqwest = { version = "0.12", default-features = false, features = ["rustls-tls", "stream"] }
dirs = "5"
sysinfo = "0.31"

[dev-dependencies]
tempfile = "3"
```

（保留模板里的 tauri-build、serde_json 等既有项，合并去重。）

- [ ] **Step 2: 写失败测试**

`runner.rs` 底部：

```rust
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
```

（第三个用例仅 Windows；若在 macOS 执行验证，临时换 `["-c", "echo hello"]` + program `sh`。）

- [ ] **Step 3: 运行确认失败**

Run: `cd deployer/src-tauri && cargo test runner`
Expected: 编译失败——`CmdSpec`/`RunOutput`/`FakeRunner` 未定义。

- [ ] **Step 4: 实现 runner.rs**

```rust
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
        use std::os::windows::process::CommandExt;
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
```

`lib.rs` 顶部加 `mod runner;`。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd deployer/src-tauri && cargo test runner`
Expected: 3 passed。

- [ ] **Step 6: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri
git commit -m "feat: 子进程抽象 CommandRunner——真实现流式输出 + 测试用 FakeRunner"
```

---

### Task 4: config.rs 向导配置与 .env 生成

**Files:**
- Create: `deployer/src-tauri/src/config.rs`
- Modify: `deployer/src-tauri/src/lib.rs`
- Test: `deployer/src-tauri/src/config.rs`（内联）

**Interfaces:**
- Consumes: 无（纯逻辑）
- Produces:

```rust
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WizardConfig { pub port: u16, pub admin_password: String, pub es_heap_gb: u32, pub backend_heap_gb: u32, pub app_version: String }
pub const DEFAULT_APP_VERSION: &str = "0.1.0";
pub fn default_config() -> WizardConfig;                      // port=80, es=2, backend=1, version=0.1.0, 密码空
pub fn validate(cfg: &WizardConfig) -> Result<(), Vec<String>>; // 密码≥8；heap 1..=16；版本 ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$
pub fn generate_jwt_secret() -> String;   // 48 字节随机 → 96 位 hex
pub fn generate_db_password() -> String;  // 24 位字母数字
pub fn render_env(cfg: &WizardConfig, jwt: &str, db_pw: &str) -> String;
```

- [ ] **Step 1: 写失败测试**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_rejects_short_password_and_bad_heap_and_bad_version() {
        let mut cfg = default_config();
        cfg.admin_password = "123".into();
        let errs = validate(&cfg).unwrap_err();
        assert!(errs.iter().any(|e| e.contains("管理员密码")));

        let mut cfg = default_config();
        cfg.admin_password = "longenough".into();
        cfg.es_heap_gb = 0;
        assert!(validate(&cfg).is_err());

        let mut cfg = default_config();
        cfg.admin_password = "longenough".into();
        cfg.app_version = "bad tag!".into();
        assert!(validate(&cfg).is_err());
    }

    #[test]
    fn validate_accepts_defaults_with_8char_password() {
        let mut cfg = default_config();
        cfg.admin_password = "12345678".into();
        assert!(validate(&cfg).is_ok());
    }

    #[test]
    fn secrets_are_random_long_and_alphanumeric() {
        let a = (generate_jwt_secret(), generate_db_password());
        let b = (generate_jwt_secret(), generate_db_password());
        assert_ne!(a.0, b.0);
        assert_eq!(a.0.len(), 96);
        assert!(a.0.chars().all(|c| c.is_ascii_hexdigit()));
        assert_eq!(a.1.len(), 24);
        assert!(a.1.chars().all(|c| c.is_ascii_alphanumeric()));
    }

    #[test]
    fn render_env_contains_all_keys_with_advanced_defaults() {
        let cfg = default_config();
        let env = render_env(&cfg, "JWT_X", "DB_X");
        assert!(env.contains("TRANSDB_FRONTEND_PORT=80\n"));
        assert!(env.contains("TRANSDB_ADMIN_PASSWORD=\n") || env.contains("TRANSDB_ADMIN_PASSWORD="));
        assert!(env.contains("TRANSDB_JWT_SECRET=JWT_X\n"));
        assert!(env.contains("TRANSDB_DB_PASSWORD=DB_X\n"));
        assert!(env.contains("TRANSDB_ES_JAVA_OPTS=-Xms2g -Xmx2g\n"));
        assert!(env.contains("TRANSDB_JAVA_OPTS=-Xmx1g\n"));
        assert!(env.contains("TRANSDB_APP_VERSION=0.1.0\n"));
    }
}
```

（注意 `default_config()` 密码为空，`render_env` 不做校验——校验是 `validate` 的职责；但空密码写入 env 属非法输入，`render_env` 对空密码写出空值可接受，因为调用方 `save_config` 先 validate。）

- [ ] **Step 2: 运行确认失败**

Run: `cd deployer/src-tauri && cargo test config`
Expected: 编译失败，模块不存在。

- [ ] **Step 3: 实现 config.rs**

```rust
use rand::RngCore;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WizardConfig {
    pub port: u16,
    pub admin_password: String,
    pub es_heap_gb: u32,
    pub backend_heap_gb: u32,
    pub app_version: String,
}

pub const DEFAULT_APP_VERSION: &str = "0.1.0";

pub fn default_config() -> WizardConfig {
    WizardConfig {
        port: 80,
        admin_password: String::new(),
        es_heap_gb: 2,
        backend_heap_gb: 1,
        app_version: DEFAULT_APP_VERSION.into(),
    }
}

pub fn validate(cfg: &WizardConfig) -> Result<(), Vec<String>> {
    let mut errs = Vec::new();
    if cfg.port == 0 { errs.push("网页端口必须在 1-65535 之间".into()); }
    if cfg.admin_password.chars().count() < 8 { errs.push("管理员密码至少 8 位".into()); }
    if !(1..=16).contains(&cfg.es_heap_gb) { errs.push("ES 堆内存必须在 1-16GB".into()); }
    if !(1..=16).contains(&cfg.backend_heap_gb) { errs.push("后端 JVM 内存必须在 1-16GB".into()); }
    let v = cfg.app_version.as_bytes();
    let charset_ok = !v.is_empty()
        && v.len() <= 64
        && v[0].is_ascii_alphanumeric()
        && v.iter().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'-'));
    if !charset_ok { errs.push("镜像版本只能是字母数字与 . _ -（≤64 位）".into()); }
    if errs.is_empty() { Ok(()) } else { Err(errs) }
}

pub fn generate_jwt_secret() -> String {
    let mut bytes = [0u8; 48];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn generate_db_password() -> String {
    const CHARSET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    let mut bytes = [0u8; 24];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes.iter().map(|b| CHARSET[*b as usize % CHARSET.len()] as char).collect()
}

pub fn render_env(cfg: &WizardConfig, jwt: &str, db_pw: &str) -> String {
    format!(
        "TRANSDB_FRONTEND_PORT={port}\n\
         TRANSDB_ADMIN_PASSWORD={admin}\n\
         TRANSDB_JWT_SECRET={jwt}\n\
         TRANSDB_DB_PASSWORD={db_pw}\n\
         TRANSDB_ES_JAVA_OPTS=-Xms{es}g -Xmx{es}g\n\
         TRANSDB_JAVA_OPTS=-Xmx{be}g\n\
         TRANSDB_APP_VERSION={ver}\n",
        port = cfg.port,
        admin = cfg.admin_password,
        es = cfg.es_heap_gb,
        be = cfg.backend_heap_gb,
        ver = cfg.app_version,
    )
}
```

`lib.rs` 加 `mod config;`。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd deployer/src-tauri && cargo test config`
Expected: 4 passed。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri/src
git commit -m "feat: 向导配置模型、校验与 .env 渲染（随机 JWT/DB 密钥）"
```

---

### Task 5: state.rs 部署状态机与断点持久化

**Files:**
- Create: `deployer/src-tauri/src/state.rs`
- Modify: `deployer/src-tauri/src/lib.rs`
- Test: `deployer/src-tauri/src/state.rs`（内联）

**Interfaces:**
- Consumes: `config::WizardConfig`（Task 4）
- Produces:

```rust
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "stage", rename_all = "snake_case")]
pub enum DeployStage { CheckEnv, InstallDocker, WaitEngine, Configure, Pull, Up, Done }
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PersistedState { pub stage: DeployStage, pub deployed: bool, pub config: Option<WizardConfig> }
impl PersistedState { pub fn initial() -> Self; }
pub fn data_dir() -> std::io::Result<PathBuf>;      // dirs::data_dir().join("transdb")，并 create_dir_all
pub fn load(dir: &Path) -> PersistedState;          // 缺失/损坏一律回退 initial()
pub fn save(dir: &Path, st: &PersistedState) -> std::io::Result<()>; // state.json，tmp+rename 原子写
pub fn next_stage(stage: &DeployStage) -> Option<DeployStage>;
```

- [ ] **Step 1: 写失败测试**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stage_sequence_is_linear() {
        let mut s = DeployStage::CheckEnv;
        let mut seq = vec![s.clone()];
        while let Some(n) = next_stage(&s) { seq.push(n.clone()); s = n; }
        assert_eq!(seq.len(), 7);
        assert_eq!(seq.last().unwrap(), &DeployStage::Done);
        assert_eq!(next_stage(&DeployStage::Done), None);
    }

    #[test]
    fn save_then_load_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let st = PersistedState { stage: DeployStage::Pull, deployed: false, config: Some(crate::config::default_config()) };
        save(dir.path(), &st).unwrap();
        assert_eq!(load(dir.path()), st);
    }

    #[test]
    fn missing_or_corrupt_file_falls_back_to_initial() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(load(dir.path()), PersistedState::initial());
        std::fs::write(dir.path().join("state.json"), "{ not json").unwrap();
        assert_eq!(load(dir.path()), PersistedState::initial());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd deployer/src-tauri && cargo test state`
Expected: 编译失败。

- [ ] **Step 3: 实现 state.rs**

```rust
use crate::config::WizardConfig;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "stage", rename_all = "snake_case")]
pub enum DeployStage {
    CheckEnv,
    InstallDocker,
    WaitEngine,
    Configure,
    Pull,
    Up,
    Done,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PersistedState {
    pub stage: DeployStage,
    pub deployed: bool,
    pub config: Option<WizardConfig>,
}

impl PersistedState {
    pub fn initial() -> Self {
        Self { stage: DeployStage::CheckEnv, deployed: false, config: None }
    }
}

pub fn data_dir() -> std::io::Result<PathBuf> {
    let dir = dirs::data_dir().ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::NotFound, "无法定位系统数据目录")
    })?;
    let dir = dir.join("transdb");
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

pub fn load(dir: &Path) -> PersistedState {
    std::fs::read_to_string(dir.join("state.json"))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(PersistedState::initial)
}

pub fn save(dir: &Path, st: &PersistedState) -> std::io::Result<()> {
    let tmp = dir.join("state.json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(st)?)?;
    std::fs::rename(tmp, dir.join("state.json"))
}

pub fn next_stage(stage: &DeployStage) -> Option<DeployStage> {
    use DeployStage::*;
    Some(match stage {
        CheckEnv => InstallDocker,
        InstallDocker => WaitEngine,
        WaitEngine => Configure,
        Configure => Pull,
        Pull => Up,
        Up => Done,
        Done => return None,
    })
}
```

`lib.rs` 加 `mod state;`。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd deployer/src-tauri && cargo test state`
Expected: 3 passed。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri/src
git commit -m "feat: 部署状态机与断点持久化 state.json"
```

---

### Task 6: docker.rs 检测、启动与引擎等待

**Files:**
- Create: `deployer/src-tauri/src/docker.rs`
- Modify: `deployer/src-tauri/src/lib.rs`
- Test: `deployer/src-tauri/src/docker.rs`（内联）

**Interfaces:**
- Consumes: `runner::{CmdSpec, CommandRunner, RunOutput, FakeRunner}`（Task 3）
- Produces:

```rust
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DockerStatus { pub installed: bool, pub engine_ready: bool }
pub async fn probe(runner: &dyn CommandRunner) -> DockerStatus;
pub async fn start_docker_desktop(runner: &dyn CommandRunner) -> bool;
pub async fn wait_engine(runner: &dyn CommandRunner, timeout: Duration, poll_every: Duration, on_wait: impl Fn(u32) + Send + Sync) -> bool;
```

- [ ] **Step 1: 写失败测试**

```rust
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
```

- [ ] **Step 2: 运行确认失败**

Run: `cd deployer/src-tauri && cargo test docker`
Expected: 编译失败。

- [ ] **Step 3: 实现 docker.rs（本任务只做检测/启动/等待，安装属 Task 7）**

```rust
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

/// 已安装但引擎未运行时拉起 Docker Desktop（macOS：open -a Docker；Windows：直接 spawn exe）
pub async fn start_docker_desktop(runner: &dyn CommandRunner) -> bool {
    #[cfg(target_os = "macos")]
    let spec = CmdSpec::new("open", &["-a", "Docker"]);
    #[cfg(windows)]
    let spec = CmdSpec::new(DOCKER_DESKTOP_APP, &[]);
    #[cfg(not(any(windows, target_os = "macos")))]
    let spec = CmdSpec::new("true", &[]);
    runner.run(spec).await.success()
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
```

`lib.rs` 加 `mod docker;`。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd deployer/src-tauri && cargo test docker`
Expected: 4 passed。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri/src
git commit -m "feat: Docker 检测、Docker Desktop 拉起与引擎就绪轮询"
```

---

### Task 7: docker.rs 安装器下载与安装（Windows/macOS）

**Files:**
- Modify: `deployer/src-tauri/src/docker.rs`
- Test: `deployer/src-tauri/src/docker.rs`（内联，测纯函数）

**Interfaces:**
- Consumes: `CommandRunner`（Task 3）
- Produces:

```rust
pub fn installer_url(os: &str, arch: &str) -> String; // win+amd64 / macos+arm64 / macos+x86_64
pub fn parse_mount_point(hdiutil_out: &str) -> Option<String>; // macOS 挂载点解析
pub fn wsl_install_spec() -> CmdSpec; // 管理员运行 wsl --install
pub async fn download_file(url: &str, dest: &std::path::Path, on_progress: impl Fn(u64, u64) + Send + Sync) -> Result<(), String>; // reqwest 流式 + 进度回调
#[cfg(windows)] pub async fn install_windows(runner: &dyn CommandRunner, installer: &std::path::Path, on_line: impl Fn(&str) + Send + Sync) -> RunOutput;
#[cfg(target_os = "macos")] pub async fn install_macos(runner: &dyn CommandRunner, dmg: &std::path::Path, on_line: impl Fn(&str) + Send + Sync) -> Result<(), String>;
```

- [ ] **Step 1: 写失败测试（纯函数部分）**

```rust
#[test]
fn installer_url_covers_three_platforms() {
    assert_eq!(installer_url("windows", "x86_64"), "https://desktop.docker.com/win/main/amd64/Docker Desktop Installer.exe");
    assert_eq!(installer_url("macos", "aarch64"), "https://desktop.docker.com/mac/main/arm64/Docker.dmg");
    assert_eq!(installer_url("macos", "x86_64"), "https://desktop.docker.com/mac/main/amd64/Docker.dmg");
}

#[test]
fn parse_mount_point_finds_docker_mount() {
    let out = "expected   CRC-32 ...\n/dev/disk4s1  Apple_HFS /Volumes/Docker\n";
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
```

- [ ] **Step 2: 运行确认失败**

Run: `cd deployer/src-tauri && cargo test docker`
Expected: 新增 3 个用例编译失败。

- [ ] **Step 3: 实现安装逻辑**

在 `docker.rs` 追加：

```rust
use crate::runner::{CmdSpec, CommandRunner, RunOutput};
use std::path::Path;

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

pub async fn download_file(
    url: &str,
    dest: &Path,
    on_progress: impl Fn(u64, u64) + Send + Sync,
) -> Result<(), String> {
    let resp = reqwest::get(url).await.map_err(|e| format!("下载失败：{e}"))?;
    let total = resp.content_length().unwrap_or(0);
    use futures_util::StreamExt;
    let mut stream = resp.bytes_stream();
    let mut file = tokio::fs::File::create(dest).await.map_err(|e| format!("创建文件失败：{e}"))?;
    use tokio::io::AsyncWriteExt;
    let mut downloaded: u64 = 0;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("下载中断：{e}"))?;
        file.write_all(&chunk).await.map_err(|e| format!("写入失败：{e}"))?;
        downloaded += chunk.len() as u64;
        on_progress(downloaded, total);
    }
    Ok(())
}

#[cfg(windows)]
pub async fn install_windows(
    runner: &dyn CommandRunner,
    installer: &Path,
    on_line: impl Fn(&str) + Send + Sync,
) -> RunOutput {
    runner
        .run_streaming(CmdSpec::new(installer.to_string_lossy().as_ref(), &["install", "--quiet", "--accept-license"]), on_line)
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
```

Cargo.toml 追加 `futures-util = "0.3"`（download_file 的 StreamExt）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd deployer/src-tauri && cargo test docker`
Expected: 7 passed（Task 6 的 4 个 + 本任务 3 个）。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri
git commit -m "feat: Docker Desktop 安装器下载与平台安装流程（Win 静默/mac DMG）"
```

---

### Task 8: compose.rs 命令封装与输出解析

**Files:**
- Create: `deployer/src-tauri/src/compose.rs`
- Modify: `deployer/src-tauri/src/lib.rs`
- Test: `deployer/src-tauri/src/compose.rs`（内联）

**Interfaces:**
- Consumes: `runner::{CmdSpec, CommandRunner, RunOutput}`（Task 3）
- Produces:

```rust
pub const PROJECT_NAME: &str = "transdb";
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ContainerStatus { pub service: String, pub state: String, pub health: Option<String> }
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct PullEvent { pub service: String, pub phase: String, pub detail: String } // phase: Pulling|Downloading|Pulled 等
pub fn base_args(project_dir: &Path) -> Vec<String>; // ["compose","-p","transdb","--project-directory",<dir>]
pub fn materialize_project(data_dir: &Path, compose_yml: &str, env_content: &str) -> std::io::Result<PathBuf>; // 写 docker-compose.yml + .env
pub async fn pull(runner, project_dir, on_line) -> RunOutput;
pub async fn up_wait(runner, project_dir, on_line) -> RunOutput;  // up -d --wait
pub async fn stop / start / restart / down(runner, project_dir, remove_volumes: bool) -> RunOutput;
pub async fn logs(runner, project_dir, service: &str) -> String;  // --tail 500 --no-color
pub async fn ps(runner, project_dir) -> Vec<ContainerStatus>;
pub fn parse_ps_output(raw: &str) -> Vec<ContainerStatus>;  // 兼容 JSON 数组与 JSON Lines
pub fn parse_pull_line(line: &str) -> Option<PullEvent>;
```

- [ ] **Step 1: 写失败测试**

```rust
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
```

- [ ] **Step 2: 运行确认失败**

Run: `cd deployer/src-tauri && cargo test compose`
Expected: 编译失败。

- [ ] **Step 3: 实现 compose.rs**

```rust
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

pub async fn pull(runner: &dyn CommandRunner, dir: &Path, on_line: impl Fn(&str) + Send + Sync) -> RunOutput {
    runner.run_streaming(spec(dir, &["pull", "--progress=plain"]), on_line).await
}

pub async fn up_wait(runner: &dyn CommandRunner, dir: &Path, on_line: impl Fn(&str) + Send + Sync) -> RunOutput {
    runner.run_streaming(spec(dir, &["up", "-d", "--wait"]), on_line).await
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

pub async fn logs(runner: &dyn CommandRunner, dir: &Path, service: &str) -> String {
    runner.run(spec(dir, &["logs", "--no-color", "--tail", "500", service])).await.stdout
}

pub async fn ps(runner: &dyn CommandRunner, dir: &Path) -> Vec<ContainerStatus> {
    let out = runner.run(spec(dir, &["ps", "--all", "--format", "json"])).await;
    parse_ps_output(&out.stdout)
}

#[derive(Deserialize)]
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
        raw.lines()
            .map(|l| serde_json::from_str::<RawPs>(l).ok())
            .collect::<Option<Vec<_>>>()
    };
    parsed
        .unwrap_or_default()
        .into_iter()
        .map(|r| ContainerStatus { service: r.service, state: r.state, health: r.health })
        .collect()
}

pub fn parse_pull_line(line: &str) -> Option<PullEvent> {
    let (service, rest) = line.split_once(' ')?;
    if service.is_empty() || !rest.starts_with(('P', 'V', 'E')) {
        return None;
    }
    let phase = rest.split(' ').next().unwrap_or_default();
    if !matches!(phase, "Pulling" | "Pulled" | "Downloading" | "Extracting" | "Verifying" | "Downloaded" | "Error") {
        return None;
    }
    let detail = rest[phase.len()..].trim().to_string();
    Some(PullEvent { service: service.into(), phase: phase.into(), detail })
}
```

`lib.rs` 加 `mod compose;`。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd deployer/src-tauri && cargo test compose`
Expected: 5 passed。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri/src
git commit -m "feat: docker compose 子进程封装——pull/up/ps/logs/停止组与输出解析"
```

---

### Task 9: Tauri command 桥接层与事件流

**Files:**
- Create: `deployer/src-tauri/src/commands.rs`
- Modify: `deployer/src-tauri/src/lib.rs`（装配 AppState 与 invoke_handler）
- Test: `deployer/src-tauri/src/commands.rs`（内联，仅测纯辅助函数）

**Interfaces:**
- Consumes: Task 3–8 全部模块
- Produces（前端 invoke 名称与载荷，Task 10 依赖）：

```
invoke("get_app_state") -> PersistedState
invoke("check_env") -> EnvReport { os_ok: bool, mem_gb: f32, mem_ok: bool, disk_free_gb: f32, disk_ok: bool, net_ok: bool, port: u16, port_free: bool }
invoke("docker_probe") -> DockerStatus
invoke("ensure_docker") -> Result<DockerStatus, String>   // 未装→安装（事件），未运行→拉起+等待
invoke("install_wsl2") -> Result<(), String>
invoke("save_config", { config: WizardConfig }) -> Result<(), String>
invoke("start_deploy") -> Result<DeployOutcome { url: String }, String>
invoke("open_web", { url: String }) -> Result<(), String>
invoke("reset_state") -> Result<(), String>   // 开发调试用：清 state.json
事件 "deploy://progress" 载荷：{ stage: String, message: String, pull: PullEvent | null, download: { downloaded: u64, total: u64 } | null }
```

Rust 侧 `AppState`：`pub struct AppState { pub runner: Arc<dyn CommandRunner>, pub data_dir: PathBuf, pub state: Mutex<PersistedState> }`

- [ ] **Step 1: 写失败测试（辅助函数）**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn friendly_install_error_detects_wsl() {
        let e = RunOutput::fail(1, "the installer requires WSL 2 to be installed");
        assert!(install_error_hint(&e).contains("WSL2"));
        let e2 = RunOutput::fail(1, "some other failure");
        assert!(!install_error_hint(&e2).contains("WSL2"));
    }

    #[test]
    fn deploy_url_reflects_port() {
        assert_eq!(deploy_url(80), "http://localhost");
        assert_eq!(deploy_url(8080), "http://localhost:8080");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd deployer/src-tauri && cargo test commands`
Expected: 编译失败。

- [ ] **Step 3: 实现 commands.rs**

```rust
use crate::compose::{self, PullEvent};
use crate::config::{self, WizardConfig};
use crate::docker;
use crate::runner::{CommandRunner, RealRunner, RunOutput};
use crate::state::{self, DeployStage, PersistedState};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};

pub struct AppState {
    pub runner: Arc<dyn CommandRunner>,
    pub data_dir: PathBuf,
    pub state: Mutex<PersistedState>,
}

#[derive(Serialize)]
pub struct EnvReport {
    pub os_ok: bool,
    pub mem_gb: f32,
    pub mem_ok: bool,
    pub disk_free_gb: f32,
    pub disk_ok: bool,
    pub net_ok: bool,
    pub port: u16,
    pub port_free: bool,
}

#[derive(Serialize, Clone)]
pub struct ProgressEvent {
    pub stage: String,
    pub message: String,
    pub pull: Option<PullEvent>,
    pub download: Option<DownloadProgress>,
}

#[derive(Serialize, Clone)]
pub struct DownloadProgress { pub downloaded: u64, pub total: u64 }

#[derive(Serialize)]
pub struct DeployOutcome { pub url: String }

fn emit(app: &AppHandle, ev: ProgressEvent) {
    let _ = app.emit("deploy://progress", ev);
}

pub fn install_error_hint(out: &RunOutput) -> String {
    let msg = format!("{}{}", out.stdout, out.stderr).to_lowercase();
    if msg.contains("wsl") {
        format!("Docker 安装失败：缺少 WSL2 组件。{}", "请点击「一键安装 WSL2」，安装完成后重启电脑再重试。")
    } else if msg.contains("space") {
        "Docker 安装失败：磁盘空间不足。".into()
    } else {
        format!("Docker 安装失败：{}", out.stderr.trim())
    }
}

pub fn deploy_url(port: u16) -> String {
    if port == 80 { "http://localhost".into() } else { format!("http://localhost:{port}") }
}

fn stage_name(s: &DeployStage) -> String {
    serde_json::to_value(s).ok()
        .and_then(|v| v.get("stage").and_then(|s| s.as_str().map(String::from)))
        .unwrap_or_default()
}

#[tauri::command]
pub async fn get_app_state(st: State<'_, AppState>) -> Result<PersistedState, String> {
    Ok(st.state.lock().unwrap().clone())
}
```

```rust
#[tauri::command]
pub async fn check_env(app: AppHandle, st: State<'_, AppState>) -> Result<EnvReport, String> {
    let port = st.state.lock().unwrap().config.as_ref().map(|c| c.port).unwrap_or(80);
    let port_free = std::net::TcpListener::bind(("127.0.0.1", port)).is_ok();
    let os_ok = cfg!(any(windows, target_os = "macos"));
    let mut sys = sysinfo::System::new_all();
    sys.refresh_all();
    let mem_gb = sys.total_memory() as f32 / 1024.0 / 1024.0 / 1024.0;
    let disk_free_gb = sysinfo::Disks::new_with_refreshed_list()
        .list().iter()
        .map(|d| d.available_space())
        .max().unwrap_or(0) as f32 / 1024.0 / 1024.0 / 1024.0;
    let net_ok = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build().map_err(|e| e.to_string())?
        .head("https://desktop.docker.com").await
        .map(|r| r.status().is_success() || r.status().as_u16() == 403)
        .unwrap_or(false);
    let report = EnvReport {
        os_ok,
        mem_gb,
        mem_ok: mem_gb >= 7.5,
        disk_free_gb,
        disk_ok: disk_free_gb >= 15.0,
        net_ok,
        port,
        port_free,
    };
    emit(&app, ProgressEvent { stage: "check_env".into(), message: format!("环境检测完成：内存 {mem_gb:.1}GB，磁盘 {disk_free_gb:.1}GB"), pull: None, download: None });
    Ok(report)
}

#[tauri::command]
pub async fn docker_probe(st: State<'_, AppState>) -> Result<docker::DockerStatus, String> {
    Ok(docker::probe(st.runner.as_ref()).await)
}

#[tauri::command]
pub async fn ensure_docker(app: AppHandle, st: State<'_, AppState>) -> Result<docker::DockerStatus, String> {
    let mut status = docker::probe(st.runner.as_ref()).await;
    if !status.installed {
        emit(&app, ProgressEvent { stage: "install_docker".into(), message: "开始下载 Docker Desktop 安装包…".into(), pull: None, download: None });
        let url = docker::installer_url(std::env::consts::OS, std::env::consts::ARCH);
        let dest = st.data_dir.join(if cfg!(windows) { "DockerDesktopInstaller.exe" } else { "Docker.dmg" });
        let app_h = app.clone();
        docker::download_file(&url, &dest, move |d, t| {
            emit(&app_h, ProgressEvent { stage: "install_docker".into(), message: "正在下载 Docker Desktop".into(), pull: None, download: Some(DownloadProgress { downloaded: d, total: t }) });
        }).await?;
        emit(&app, ProgressEvent { stage: "install_docker".into(), message: "下载完成，开始安装（可能弹出系统确认框）…".into(), pull: None, download: None });
        let app_h = app.clone();
        let install_res = {
            #[cfg(windows)]
            { docker::install_windows(st.runner.as_ref(), &dest, move |l| emit(&app_h, ProgressEvent { stage: "install_docker".into(), message: l.to_string(), pull: None, download: None })).await }
            #[cfg(target_os = "macos")]
            { match docker::install_macos(st.runner.as_ref(), &dest, move |l| emit(&app_h, ProgressEvent { stage: "install_docker".into(), message: l.to_string(), pull: None, download: None })).await {
                Ok(()) => RunOutput::ok("installed"),
                Err(e) => RunOutput::fail(1, &e),
            } }
        };
        if !install_res.success() {
            return Err(install_error_hint(&install_res));
        }
        status = docker::probe(st.runner.as_ref()).await;
    }
    if !status.engine_ready {
        emit(&app, ProgressEvent { stage: "wait_engine".into(), message: "正在启动 Docker 引擎（首次约 30-60 秒）…".into(), pull: None, download: None });
        #[cfg(target_os = "macos")]
        emit(&app, ProgressEvent { stage: "wait_engine".into(), message: "若弹出 Docker 服务条款窗口，请点击「接受」".into(), pull: None, download: None });
        if !status.installed || docker::start_docker_desktop(st.runner.as_ref()).await {
            let app_h = app.clone();
            let ready = docker::wait_engine(st.runner.as_ref(), Duration::from_secs(600), Duration::from_secs(2), move |tick| {
                emit(&app_h, ProgressEvent { stage: "wait_engine".into(), message: format!("等待引擎就绪…（已等待 {} 秒）", tick * 2), pull: None, download: None });
            }).await;
            if !ready {
                return Err("Docker 引擎长时间未就绪。请打开 Docker Desktop 查看其界面提示后重试。".into());
            }
        } else {
            return Err("启动 Docker Desktop 失败，请手动打开它后点击重试。".into());
        }
        status = docker::DockerStatus { installed: true, engine_ready: true };
    }
    let mut ps = st.state.lock().unwrap();
    ps.stage = DeployStage::Configure;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(status)
}

#[tauri::command]
pub async fn install_wsl2(st: State<'_, AppState>) -> Result<(), String> {
    let out = st.runner.run(docker::wsl_install_spec()).await;
    if out.success() { Ok(()) } else { Err(format!("WSL2 安装命令启动失败：{}", out.stderr)) }
}

#[tauri::command]
pub async fn save_config(app: AppHandle, st: State<'_, AppState>, config: WizardConfig) -> Result<(), String> {
    config::validate(&config).map_err(|e| e.join("；"))?;
    let env = config::render_env(&config, &config::generate_jwt_secret(), &config::generate_db_password());
    let compose_yml = std::fs::read_to_string(
        app.path().resource_dir().map_err(|e| e.to_string())?.join("docker-compose.yml"),
    ).map_err(|e| format!("读取内置 compose 资源失败：{e}"))?;
    compose::materialize_project(&st.data_dir, &compose_yml, &env).map_err(|e| e.to_string())?;
    let mut ps = st.state.lock().unwrap();
    ps.config = Some(config);
    ps.stage = DeployStage::Pull;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn start_deploy(app: AppHandle, st: State<'_, AppState>) -> Result<DeployOutcome, String> {
    let port = st.state.lock().unwrap().config.as_ref().map(|c| c.port).unwrap_or(80);
    emit(&app, ProgressEvent { stage: "pull".into(), message: "开始拉取镜像（约 1.7GB，视网速）…".into(), pull: None, download: None });
    let app_h = app.clone();
    let pull_out = compose::pull(st.runner.as_ref(), &st.data_dir, move |line| {
        if let Some(ev) = compose::parse_pull_line(line) {
            emit(&app_h, ProgressEvent { stage: "pull".into(), message: format!("{} {}", ev.service, ev.phase), pull: Some(ev), download: None });
        }
    }).await;
    if !pull_out.success() {
        return Err(format!("镜像拉取失败：{}", pull_out.stderr.trim()));
    }
    {
        let mut ps = st.state.lock().unwrap();
        ps.stage = DeployStage::Up;
        state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    }
    emit(&app, ProgressEvent { stage: "up".into(), message: "启动容器（PG/ES 就绪后自动起后端，约 1-2 分钟）…".into(), pull: None, download: None });
    let app_h = app.clone();
    let up_out = compose::up_wait(st.runner.as_ref(), &st.data_dir, move |line| {
        emit(&app_h, ProgressEvent { stage: "up".into(), message: line.to_string(), pull: None, download: None });
    }).await;
    if !up_out.success() {
        return Err(format!("容器启动失败：{}", up_out.stderr.trim()));
    }
    let mut ps = st.state.lock().unwrap();
    ps.stage = DeployStage::Done;
    ps.deployed = true;
    state::save(&st.data_dir, &ps).map_err(|e| e.to_string())?;
    Ok(DeployOutcome { url: deploy_url(port) })
}

#[tauri::command]
pub async fn open_web(app: AppHandle, url: String) -> Result<(), String> {
    app.opener().open_url(url).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn reset_state(st: State<'_, AppState>) -> Result<(), String> {
    let _ = std::fs::remove_file(st.data_dir.join("state.json"));
    *st.state.lock().unwrap() = PersistedState::initial();
    Ok(())
}
```

（`check_env` 的磁盘统计两平台统一走 `sysinfo::Disks`，取所有挂载盘最大可用空间，无平台分支。）

`lib.rs` 装配：

```rust
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
```

capabilities/default.json 追加权限：

```json
{
  "identifier": "default",
  "windows": ["main"],
  "permissions": ["core:default", "opener:default"]
}
```

- [ ] **Step 4: 运行测试确认通过 + 编译**

Run: `cd deployer/src-tauri && cargo test`
Expected: 全部通过（此前所有模块 + commands 2 个）。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri
git commit -m "feat: Tauri command 桥接——环境检测/Docker 保障/配置落盘/部署执行与进度事件"
```

---

### Task 10: 向导五步 UI（Vue 3 + Element Plus）

**Files:**
- Create: `deployer/src/wizard/types.ts`、`deployer/src/wizard/validate.ts`、`deployer/src/api/deployer.ts`、`deployer/src/wizard/WizardShell.vue`、`deployer/src/wizard/StepEnvCheck.vue`、`deployer/src/wizard/StepDocker.vue`、`deployer/src/wizard/StepConfig.vue`、`deployer/src/wizard/StepDeploy.vue`、`deployer/src/wizard/StepDone.vue`
- Modify: `deployer/src/App.vue`
- Test: `deployer/src/wizard/validate.test.ts`、`deployer/src/wizard/StepConfig.test.ts`

**Interfaces:**
- Consumes: Task 9 的 invoke 协议与 `deploy://progress` 事件
- Produces: 完整向导流程；`validate.ts` 导出 `validateConfig(cfg): string[]`（与 Rust `validate` 同规则的前端镜像）；`api/deployer.ts` 导出全部类型化 invoke 包装。

- [ ] **Step 1: 写失败测试（validate.ts）**

`deployer/src/wizard/validate.test.ts`：

```typescript
import { describe, it, expect } from 'vitest'
import { validateConfig, defaultWizardConfig } from './validate'

describe('validateConfig', () => {
  it('默认配置 + 8 位密码通过', () => {
    expect(validateConfig({ ...defaultWizardConfig(), adminPassword: '12345678' })).toEqual([])
  })
  it('短密码报错', () => {
    const errs = validateConfig({ ...defaultWizardConfig(), adminPassword: '123' })
    expect(errs.some((e) => e.includes('管理员密码'))).toBe(true)
  })
  it('堆内存越界与非法镜像版本报错', () => {
    const errs = validateConfig({ ...defaultWizardConfig(), adminPassword: '12345678', esHeapGb: 0 })
    expect(errs.length).toBeGreaterThan(0)
    expect(validateConfig({ ...defaultWizardConfig(), adminPassword: '12345678', appVersion: 'bad tag!' }).length).toBeGreaterThan(0)
  })
})
```

Run: `cd deployer && npm test`
Expected: FAIL（validate.ts 不存在）。

- [ ] **Step 2: 实现 types/validate/api**

`deployer/src/wizard/types.ts`：

```typescript
export interface WizardConfig {
  port: number
  admin_password: string
  es_heap_gb: number
  backend_heap_gb: number
  app_version: string
}
export interface EnvReport {
  os_ok: boolean
  mem_gb: number
  mem_ok: boolean
  disk_free_gb: number
  disk_ok: boolean
  net_ok: boolean
  port: number
  port_free: boolean
}
export interface DockerStatus { installed: boolean; engine_ready: boolean }
export interface PullEvent { service: string; phase: string; detail: string }
export interface ProgressEvent {
  stage: string
  message: string
  pull: PullEvent | null
  download: { downloaded: number; total: number } | null
}
export interface PersistedState {
  stage: string
  deployed: boolean
  config: WizardConfig | null
}
```

`deployer/src/wizard/validate.ts`：

```typescript
import type { WizardConfig } from './types'

export function defaultWizardConfig(): WizardConfig {
  return { port: 80, admin_password: '', es_heap_gb: 2, backend_heap_gb: 1, app_version: '0.1.0' }
}

export function validateConfig(cfg: WizardConfig): string[] {
  const errs: string[] = []
  if (!Number.isInteger(cfg.port) || cfg.port < 1 || cfg.port > 65535) errs.push('网页端口必须在 1-65535 之间')
  if (cfg.admin_password.length < 8) errs.push('管理员密码至少 8 位')
  if (!(cfg.es_heap_gb >= 1 && cfg.es_heap_gb <= 16)) errs.push('ES 堆内存必须在 1-16GB')
  if (!(cfg.backend_heap_gb >= 1 && cfg.backend_heap_gb <= 16)) errs.push('后端 JVM 内存必须在 1-16GB')
  if (!/^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$/.test(cfg.app_version)) errs.push('镜像版本只能是字母数字与 . _ -（≤64 位）')
  return errs
}
```

`deployer/src/api/deployer.ts`：

```typescript
import { invoke } from '@tauri-apps/api/core'
import type { DockerStatus, EnvReport, PersistedState, ProgressEvent, WizardConfig } from '../wizard/types'

export const getAppState = () => invoke<PersistedState>('get_app_state')
export const checkEnv = () => invoke<EnvReport>('check_env')
export const dockerProbe = () => invoke<DockerStatus>('docker_probe')
export const ensureDocker = () => invoke<DockerStatus>('ensure_docker')
export const installWsl2 = () => invoke<void>('install_wsl2')
export const saveConfig = (config: WizardConfig) => invoke<void>('save_config', { config })
export const startDeploy = () => invoke<{ url: string }>('start_deploy')
export const openWeb = (url: string) => invoke<void>('open_web', { url })
export const resetState = () => invoke<void>('reset_state')
export { listen as listenProgress } from '@tauri-apps/api/event'
export type { ProgressEvent }
```

Run: `cd deployer && npm test`
Expected: validate 测试 PASS（3 个）。

- [ ] **Step 3: 写 StepConfig 组件测试（先失败）**

`deployer/src/wizard/StepConfig.test.ts`：

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StepConfig from './StepConfig.vue'

describe('StepConfig', () => {
  it('密码过短时提交被拦截并提示', async () => {
    const wrapper = mount(StepConfig, { global: { plugins: [ElementPlus] } })
    await wrapper.find('button.submit').trigger('click')
    expect(wrapper.text()).toContain('管理员密码至少 8 位')
  })
  it('合法输入提交时 emit config', async () => {
    const wrapper = mount(StepConfig, { global: { plugins: [ElementPlus] } })
    await wrapper.find('input.admin-password').setValue('password8')
    await wrapper.find('input.admin-password2').setValue('password8')
    await wrapper.find('button.submit').trigger('click')
    expect(wrapper.emitted('submit')![0][0]).toMatchObject({ port: 80, admin_password: 'password8' })
  })
})
```

Run: `cd deployer && npm test`
Expected: FAIL（组件不存在）。

- [ ] **Step 4: 实现五个步骤组件与外壳**

`StepConfig.vue`（核心表单，其余组件围绕它简化）：

```vue
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { defaultWizardConfig, validateConfig } from './validate'
import type { WizardConfig } from './types'

const emit = defineEmits<{ (e: 'submit', cfg: WizardConfig): void }>()
const form = reactive({ ...defaultWizardConfig() })
const password2 = ref('')
const advancedOpen = ref(false)
const errors = ref<string[]>([])

function submit() {
  const errs = validateConfig(form)
  if (password2.value !== form.admin_password) errs.push('两次输入的管理员密码不一致')
  errors.value = errs
  if (errs.length === 0) emit('submit', { ...form })
}
</script>

<template>
  <el-form label-width="130px">
    <el-form-item label="网页访问端口">
      <el-input-number v-model="form.port" :min="1" :max="65535" data-test="port" />
    </el-form-item>
    <el-form-item label="管理员密码">
      <el-input v-model="form.admin_password" type="password" show-password class="admin-password" placeholder="至少 8 位" />
    </el-form-item>
    <el-form-item label="确认密码">
      <el-input v-model="password2" type="password" show-password class="admin-password2" />
    </el-form-item>
    <el-collapse v-model="advancedOpen">
      <el-collapse-item title="高级选项（一般无需修改）" name="adv">
        <el-form-item label="ES 堆内存 (GB)"><el-input-number v-model="form.es_heap_gb" :min="1" :max="16" /></el-form-item>
        <el-form-item label="后端内存 (GB)"><el-input-number v-model="form.backend_heap_gb" :min="1" :max="16" /></el-form-item>
        <el-form-item label="镜像版本"><el-input v-model="form.app_version" /></el-form-item>
      </el-collapse-item>
    </el-collapse>
    <el-alert v-for="e in errors" :key="e" :title="e" type="error" :closable="false" style="margin: 8px 0" />
    <el-button type="primary" class="submit" @click="submit">开始部署</el-button>
  </el-form>
</template>
```

`WizardShell.vue`（状态机驱动 + 断点续跑 + 进度订阅）：

```vue
<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { listen } from '@tauri-apps/api/event'
import StepEnvCheck from './StepEnvCheck.vue'
import StepDocker from './StepDocker.vue'
import StepConfig from './StepConfig.vue'
import StepDeploy from './StepDeploy.vue'
import StepDone from './StepDone.vue'
import { getAppState, saveConfig, startDeploy } from '../api/deployer'
import type { ProgressEvent, WizardConfig } from './types'

const STAGES = ['check_env', 'install_docker', 'wait_engine', 'configure', 'pull', 'up', 'done'] as const
const current = ref<number>(0)
const deployedUrl = ref('')
const deployed = ref(false)
let unlisten: (() => void) | null = null

const view = computed(() => {
  if (deployed.value) return 'done'
  return ['env', 'docker', 'config', 'deploy', 'done'][current.value] ?? 'env'
})

const progress = ref<ProgressEvent | null>(null)

onMounted(async () => {
  unlisten = await listen<ProgressEvent>('deploy://progress', (e) => { progress.value = e.payload })
  const st = await getAppState()
  if (st.deployed) { deployed.value = true; return }
  const idx = STAGES.indexOf(st.stage as (typeof STAGES)[number])
  // CheckEnv→env 页；InstallDocker/WaitEngine→docker 页；Configure→config 页；Pull/Up→deploy 页
  current.value = idx <= 0 ? 0 : idx <= 2 ? 1 : idx === 3 ? 2 : 3
})

onUnmounted(() => unlisten?.())

async function onConfigSubmit(cfg: WizardConfig) {
  await saveConfig(cfg)
  current.value = 3
  const outcome = await startDeploy()
  deployedUrl.value = outcome.url
  deployed.value = true
}
</script>

<template>
  <el-steps :active="deployed ? 4 : current" simple style="margin-bottom: 16px">
    <el-step title="环境检测" /><el-step title="Docker" /><el-step title="配置" /><el-step title="部署" /><el-step title="完成" />
  </el-steps>
  <StepEnvCheck v-if="view === 'env'" @next="current = 1" />
  <StepDocker v-else-if="view === 'docker'" @next="current = 2" />
  <StepConfig v-else-if="view === 'config'" @submit="onConfigSubmit" />
  <StepDeploy v-else-if="view === 'deploy'" :progress="progress" @done="(u: string) => { deployedUrl = u; deployed = true }" />
  <StepDone v-else :url="deployedUrl" />
</template>
```

`StepEnvCheck.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { checkEnv } from '../api/deployer'
import type { EnvReport } from './types'
const emit = defineEmits<{ (e: 'next'): void }>()
const report = ref<EnvReport | null>(null)
const error = ref('')
onMounted(async () => { try { report.value = await checkEnv() } catch (e) { error.value = String(e) } })
</script>

<template>
  <el-descriptions v-if="report" title="环境检测" :column="1" border>
    <el-descriptions-item label="操作系统">{{ report.os_ok ? '支持' : '仅支持 Windows 10+/macOS' }}</el-descriptions-item>
    <el-descriptions-item label="内存">{{ report.mem_gb.toFixed(1) }}GB {{ report.mem_ok ? '✓' : '（低于建议 8GB，仍可继续）' }}</el-descriptions-item>
    <el-descriptions-item label="磁盘空余">{{ report.disk_free_gb.toFixed(1) }}GB {{ report.disk_ok ? '✓' : '（低于所需 15GB）' }}</el-descriptions-item>
    <el-descriptions-item label="网络">{{ report.net_ok ? '可访问下载源' : '无法访问下载源，请检查网络' }}</el-descriptions-item>
    <el-descriptions-item label="端口">{{ report.port }} {{ report.port_free ? '可用' : '被占用，请稍后在配置步更换' }}</el-descriptions-item>
  </el-descriptions>
  <el-alert v-if="error" type="error" :title="error" :closable="false" />
  <el-button type="primary" style="margin-top: 16px" :disabled="!report" @click="emit('next')">下一步</el-button>
</template>
```

`StepDocker.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { dockerProbe, ensureDocker, installWsl2 } from '../api/deployer'
const emit = defineEmits<{ (e: 'next'): void }>()
const status = ref<'checking' | 'ready' | 'working' | 'error'>('checking')
const message = ref('')
const wslError = ref(false)

async function run() {
  status.value = 'checking'
  const probe = await dockerProbe()
  if (probe.engine_ready) { status.value = 'ready'; emit('next'); return }
  status.value = 'working'
  try {
    await ensureDocker()
    status.value = 'ready'
    emit('next')
  } catch (e) {
    status.value = 'error'
    message.value = String(e)
    wslError.value = message.value.includes('WSL2')
  }
}

onMounted(run)

async function fixWsl() {
  await installWsl2()
  message.value = 'WSL2 安装命令已执行，请按系统提示完成并重启电脑，再回到本窗口重试。'
}
</script>

<template>
  <el-result v-if="status === 'ready'" icon="success" title="Docker 已就绪" />
  <el-result v-else-if="status === 'checking'" icon="info" title="正在检测 Docker…" />
  <div v-else-if="status === 'working'">
    <el-alert type="info" :title="message || '正在准备 Docker（下载/安装/启动，视网速可能较久）…'" :closable="false" />
    <el-progress indeterminate style="margin-top: 12px" />
  </div>
  <div v-else>
    <el-alert type="error" :title="message" :closable="false" />
    <el-button v-if="wslError" type="primary" style="margin-top: 12px" @click="fixWsl">一键安装 WSL2</el-button>
    <el-button style="margin-top: 12px" @click="run">重试</el-button>
  </div>
</template>
```

`StepDeploy.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { startDeploy } from '../api/deployer'
import type { ProgressEvent } from './types'
defineProps<{ progress: ProgressEvent | null }>()
const emit = defineEmits<{ (e: 'done', url: string): void }>()
const error = ref('')
const busy = ref(false)

async function run() {
  busy.value = true
  error.value = ''
  try { emit('done', (await startDeploy()).url) } catch (e) { error.value = String(e) } finally { busy.value = false }
}
onMounted(run)
</script>

<template>
  <div>
    <el-alert v-if="progress" type="info" :title="`${progress.stage}：${progress.message}`" :closable="false" />
    <el-progress v-if="busy" indeterminate style="margin: 12px 0" />
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-button v-if="error" style="margin-top: 12px" @click="run">重试本步</el-button>
  </div>
</template>
```

`StepDone.vue`：

```vue
<script setup lang="ts">
import { openWeb } from '../api/deployer'
const props = defineProps<{ url: string }>()
</script>

<template>
  <el-result icon="success" title="部署完成" sub-title="请记好管理员账号密码，忘记只能清数据重部署">
    <template #extra>
      <p>访问地址：{{ url || 'http://localhost' }}　账号：admin</p>
      <el-button type="primary" @click="openWeb(props.url || 'http://localhost')">打开网页</el-button>
    </template>
  </el-result>
</template>
```

`App.vue`：

```vue
<script setup lang="ts">
import WizardShell from './wizard/WizardShell.vue'
</script>

<template>
  <main style="padding: 24px">
    <h1>翻译数据库部署器</h1>
    <WizardShell />
  </main>
</template>
```

- [ ] **Step 5: 运行全部前端测试确认通过**

Run: `cd deployer && npm test`
Expected: validate 3 个 + StepConfig 2 个 + App 1 个全部 PASS。

（注意：`listen`/`invoke` 在 jsdom 环境不可用，`WizardShell` 的 onMounted 会在测试中报错——因此**不为 WizardShell 写挂载测试**，其正确性由 Task 11 手工验证覆盖；App.test.ts 保持 Task 2 的断言：只查标题文本。若挂载 App 时因 WizardShell 内 Tauri API 报错，把 App.test.ts 改为 `mount(App, { global: { stubs: { WizardShell: true } } })`。）

- [ ] **Step 6: 类型检查**

Run: `cd deployer && npm run build:front`
Expected: vue-tsc 无错误。

- [ ] **Step 7: 准备提交（需用户确认）**

```bash
git add deployer/src
git commit -m "feat: 向导五步 UI——环境检测/Docker 准备/配置/部署进度/完成"
```

---

### Task 11: compose 资源打包同步 + 手工验证清单

**Files:**
- Create: `deployer/scripts/sync-compose.mjs`
- Modify: `deployer/src-tauri/tauri.conf.json`（bundle.resources）、`deployer/package.json`（scripts 钩子）
- Create: `deployer/README.md`（开发说明 + 手工验证清单）

**Interfaces:**
- Consumes: Task 9 `save_config` 从 `resource_dir()/docker-compose.yml` 读取内置 compose
- Produces: `npm run tauri dev` / `tauri build` 前自动同步根 compose 到 `deployer/src-tauri/resources/docker-compose.yml` 并打包。

- [ ] **Step 1: 同步脚本**

`deployer/scripts/sync-compose.mjs`：

```javascript
import { copyFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const dest = join(root, 'deployer', 'src-tauri', 'resources', 'docker-compose.yml')
mkdirSync(dirname(dest), { recursive: true })
copyFileSync(join(root, 'docker-compose.yml'), dest)
console.log(`synced docker-compose.yml -> ${dest}`)
```

`package.json` scripts 调整：

```json
"dev:front": "node scripts/sync-compose.mjs && vite",
"build:front": "node scripts/sync-compose.mjs && vue-tsc -b && vite build"
```

`tauri.conf.json` 的 `bundle` 加：

```json
"resources": ["resources/docker-compose.yml"]
```

（`resources/` 首次生成后随代码提交。）

- [ ] **Step 2: 验证同步与资源打包**

Run: `cd deployer && npm run dev:front`（确认首行输出 `synced docker-compose.yml -> ...resources/docker-compose.yml` 且 Vite 起在 1420 端口后 Ctrl+C）
Run: `cd deployer/src-tauri && cargo check`
Expected: 编译通过。

- [ ] **Step 3: 手工验证清单（写入 deployer/README.md 并逐项执行）**

`deployer/README.md`：

```markdown
# transdb-deployer（开发说明）

一键部署器的向导核心。技术栈与目录结构见
docs/superpowers/specs/2026-09-16-oneclick-deployer-design.md。

## 开发

cd deployer && npm install
npm run tauri dev     # 开发模式（自动同步根 compose 到资源）

## 手工验证清单（每次发版前 + 本任务验收）

前置：本机 Docker Desktop 已安装且运行。

1. 全新部署：`reset_state` 后启动应用 → 五步走完（端口默认 80、密码 ≥8 位）
   → 浏览器打开 http://localhost，admin/所设密码登录成功
2. 断点续跑：部署页进行中强杀应用 → 重开应用应回到部署页（state.json stage=pull/up）
3. 已部署识别：部署完成后重启应用 → 直接显示完成页（deployed=true）
4. Docker 未运行：退出 Docker Desktop → 打开应用 Docker 步显示等待/失败提示，
   手动启动 Docker 后重试通过
5. 端口被占：先用其他程序占 8080，向导配置 8080 → 环境检测提示占用，改回 80 通过
6. `docker compose -p transdb ps` 显示 4 容器 healthy；`restart: unless-stopped` 生效
```

逐项执行并勾选（开发机执行 1/2/3/5/6；条目 4 需退出 Docker 验证；Windows 安装器/macOS DMG 真机安装路径留待计划 2 的发版冒烟，本任务用「已装 Docker」路径验证全部代码分支）。

- [ ] **Step 4: 准备提交（需用户确认）**

```bash
git add deployer
git commit -m "feat: compose 资源构建同步 + deployer 开发说明与手工验证清单"
```

---

## Self-Review 记录

- **Spec 覆盖**：计划 1 覆盖规格 §3（工程结构/compose 改造/数据落盘）、§4（向导五步/Docker 安装/断点续跑/三层自启中的容器层）、§6（单元+组件+手工冒烟的开发机子集）；§5 托盘管理、§7 构建发布、自启的 Docker 层与工具层 → 计划 2（执行完计划 1 后编写）。
- **占位符扫描**：无 TBD/TODO/占位函数；Task 9 磁盘统计为两平台统一实现，无平台分支残留。
- **类型一致性**：`WizardConfig` 字段名（port/admin_password/es_heap_gb/backend_heap_gb/app_version）在 Rust 与 TS 两侧一致；`PullEvent{service,phase,detail}`、`DockerStatus{installed,engine_ready}`、`DeployStage` serde snake_case 与前端 `STAGES` 字符串一致；`CommandRunner::run_streaming` 签名在 Task 6/7/8 调用处匹配。
- **已知执行风险**：`docker compose ps --format json` 输出格式随版本可能是数组或 JSON Lines，解析已兼容两者；Tauri 资源路径 `resource_dir()` 在 dev 与打包后均可用，Task 11 已覆盖验证。
