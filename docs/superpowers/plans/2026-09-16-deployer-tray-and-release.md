# 一键部署器·计划 2：托盘常驻、管理窗口与发布（tray, dashboard & release）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把计划 1 的"一次性向导"升级为规格承诺的最终形态：托盘常驻（三态图标/掉线监测）、管理窗口四标签页（状态/日志/设置/维护）、三层开机自启、三平台 CI 构建发布，并清偿终审 triage 的必修项与四个规格缺口。

**Architecture:** 计划 1 的模块不动骨架只做小修；新增 tray.rs（托盘与状态轮询）、autostart.rs（Docker Desktop 与工具自启）、dashboard 前端页（与向导共用 SPA，按 window label 路由）。所有 Docker 操作继续经 CommandRunner/compose 子进程封装。

**Tech Stack:** Tauri 2（tray-icon/single-instance/autostart 插件）、Vue 3 + Element Plus、GitHub Actions（windows-latest / macos-13 / macos-14）。

**Spec:** docs/superpowers/specs/2026-09-16-oneclick-deployer-design.md（本计划覆盖 §5 托盘常驻管理、§7 构建与发布、§4 剩余缺口、§2 剩余自启层；§6 冒烟清单扩为发版清单）

## Global Constraints

- **git 提交规则（用户全局硬性规则）**：每个 Commit 步骤一律改为「准备提交——停下向用户展示文件与信息，明确同意后执行」；subagent prompt 绝不包含 git 写指令，提交由主控统一执行。
- Rust ≥1.77.2；Node ≥18；npm 包管理器；前端栈同 frontend/（TS ~5.6 / Vue ^3.5 / element-plus ^2.9 / Vite ^6 / Vitest ^2）。
- compose 项目名固定 `transdb`；数据目录 Windows `%APPDATA%\transdb\`、macOS `~/Library/Application Support/transdb/`（`dirs::data_dir()`）。
- UI 文案全部中文；Rust 单元测试不依赖真实 Docker（FakeRunner 注入）；除 dead_code 外警告清零。
- 后端 invoke 协议新增命令一律 snake_case，事件名一律 `deploy://` 或 `tray://` 前缀。
- macOS 代码路径无法在本 Windows 机真机验证：每个 cfg(target_os="macos") 函数须以临时 cfg 对调编译验证后恢复（计划 1 同款做法，如实记录）；真机冒烟留发版清单。
- 依赖版本：tauri-plugin-single-instance = "2"、tauri-plugin-autostart = "2"；不引入 image/png 类依赖（托盘图标用纯函数画 RGBA 圆点）。

---

### Task 1: runner.rs stderr 并发排空（终审必修：管道缓冲死锁窗口）

**Files:**
- Modify: `deployer/src-tauri/src/runner.rs`
- Test: `deployer/src-tauri/src/runner.rs`（内联）

**Interfaces:**
- Consumes: 无
- Produces: `run_streaming` 行为变更——stdout 逐行回调的同时并发排空 stderr 并完整返回；对外签名不变（`Box<dyn for<'a> Fn(&'a str) + Send + Sync>` 版）。

- [ ] **Step 1: 写失败测试（真实现、大数据量 stderr）**

runner.rs 测试区追加（Windows 形态；macOS 执行时换 `sh -c`）：

```rust
    #[tokio::test]
    async fn real_runner_streaming_survives_fat_stderr_while_streaming_stdout() {
        // cmd 同时产出 200KB stderr 与逐行 stdout：若 stderr 只在 stdout EOF 后排空，
        // 子进程会写满管道缓冲而卡死；外层 20s 超时把"死锁"转化为确定性失败
        let script = "for /L %i in (1,1,2000) do @echo 0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789 1>&2 & echo out%i";
        let real = RealRunner;
        let fut = real.run_streaming(CmdSpec::new("cmd", &["/C", script]), Box::new(|_| {}));
        let out = tokio::time::timeout(std::time::Duration::from_secs(20), fut)
            .await
            .expect("20s 超时：stderr 未并发排空导致管道死锁");
        assert!(out.success());
        assert_eq!(out.stdout.lines().count(), 2000);
        assert!(out.stderr.len() > 100_000);
    }
```

- [ ] **Step 2: 运行确认现状**

Run: `cd deployer/src-tauri && cargo test --release real_runner_streaming_survives -- --nocapture`（release 避免超时误报；若 20s 内卡死或失败为 RED）
Expected: 卡死至超时或失败——证明死锁窗口存在。若竟通过，记录"当前缓冲未触达"仍执行 Step 3 改造（防御性修复）。

- [ ] **Step 3: 实现——stderr 用独立任务并发排空**

`run_streaming` 实现改为：

```rust
    async fn run_streaming(&self, spec: CmdSpec, on_line: Box<dyn for<'a> Fn(&'a str) + Send + Sync>) -> RunOutput {
        let mut cmd = base_command(&spec);
        cmd.stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped());
        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(_) => return RunOutput { code: None, stdout: String::new(), stderr: String::new() },
        };
        let stderr_handle = child.stderr.take();
        // stderr 独立任务并发排空：否则子进程写满 stderr 管道缓冲（~64KB）会死锁
        let stderr_task = tokio::spawn(async move {
            let mut buf = String::new();
            if let Some(mut se) = stderr_handle {
                use tokio::io::AsyncReadExt;
                let mut bytes = Vec::new();
                let _ = se.read_to_end(&mut bytes).await;
                buf = String::from_utf8_lossy(&bytes).into_owned();
            }
            buf
        });
        let mut stdout_lines = Vec::new();
        if let Some(stdout) = child.stdout.take() {
            let mut reader = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = reader.next_line().await {
                on_line(&line);
                stdout_lines.push(line);
            }
        }
        let status = child.wait().await;
        let stderr = stderr_task.await.unwrap_or_default();
        match status {
            Ok(st) => RunOutput { code: st.code(), stdout: stdout_lines.join("\n"), stderr },
            Err(_) => RunOutput { code: None, stdout: stdout_lines.join("\n"), stderr },
        }
    }
```

（`wait_with_output` 不再适用——stdout/stderr 已被手动取走；`wait()` 后各管道自然 EOF。）

- [ ] **Step 4: 全量回归**

Run: `cd deployer/src-tauri && cargo test`
Expected: 26 passed（25 + 新增 1），无非 dead_code 警告。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri/src/runner.rs
git commit -m "fix: run_streaming 并发排空 stderr——消除 64KB 管道缓冲死锁窗口"
```

---

### Task 2: 小修包——JSONL 坏行跳过 + start_deploy 守卫 + state.json 剥密码（终审 triage）

**Files:**
- Modify: `deployer/src-tauri/src/compose.rs`、`deployer/src-tauri/src/commands.rs`、`deployer/src-tauri/src/state.rs`
- Test: 上述三文件内联测试

**Interfaces:**
- Consumes: 计划 1 的 `parse_ps_output` / `start_deploy` / `save`
- Produces: `parse_ps_output` 语义变为「数组整体解析失败→空；JSONL 逐行解析、坏行跳过」；`start_deploy` 在 `config: None` 时返回中文错误；`state.json` 不再含明文 `admin_password`（持久化为空串，内存不变）。

- [ ] **Step 1: 写失败测试**

compose.rs 测试追加：

```rust
    #[test]
    fn parse_ps_jsonl_skips_bad_line_but_keeps_good_ones() {
        let raw = "{\"Service\":\"backend\",\"State\":\"running\"}\n不是JSON\n{\"Service\":\"postgres\",\"State\":\"running\"}\n";
        let ps = parse_ps_output(raw);
        assert_eq!(ps.len(), 2);
        assert_eq!(ps[0].service, "backend");
        assert_eq!(ps[1].service, "postgres");
    }
```

state.rs 测试追加：

```rust
    #[test]
    fn saved_state_never_contains_admin_password() {
        let dir = tempfile::tempdir().unwrap();
        let mut cfg = crate::config::default_config();
        cfg.admin_password = "super-secret-pw".into();
        save(dir.path(), &PersistedState { stage: DeployStage::Pull, deployed: false, config: Some(cfg) }).unwrap();
        let raw = std::fs::read_to_string(dir.path().join("state.json")).unwrap();
        assert!(!raw.contains("super-secret-pw"));
    }
```

- [ ] **Step 2: 确认失败**

Run: `cd deployer/src-tauri && cargo test parse_ps_jsonl_skips && cargo test saved_state_never_contains`
Expected: 前者 FAIL（坏行致整批 None→空）；后者 FAIL（密码明文在 JSON）。

- [ ] **Step 3: 实现**

compose.rs `parse_ps_output` 的 JSONL 分支改为逐行 filter_map：

```rust
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
```

state.rs `save` 在序列化前剥密码：

```rust
pub fn save(dir: &Path, st: &PersistedState) -> std::io::Result<()> {
    // state.json 只供断点续跑与端口展示使用；admin_password 明文不落此文件（.env 已有）
    let mut to_disk = st.clone();
    if let Some(c) = &mut to_disk.config {
        c.admin_password = String::new();
    }
    let tmp = dir.join("state.json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(&to_disk)?)?;
    std::fs::rename(tmp, dir.join("state.json"))
}
```

commands.rs `start_deploy` 开头加守卫（`st.state.lock()` 取 config 后立即释放）：

```rust
    let port = {
        let ps = st.state.lock().unwrap();
        match &ps.config {
            Some(c) => c.port,
            None => return Err("尚未保存部署配置，请先完成配置步骤".into()),
        }
    };
```

- [ ] **Step 4: 回归**

Run: `cd deployer/src-tauri && cargo test`
Expected: 28 passed（26 + 2）。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri/src/compose.rs deployer/src-tauri/src/commands.rs deployer/src-tauri/src/state.rs
git commit -m "fix: ps 解析坏行跳过、start_deploy 配置守卫、state.json 不再存明文密码"
```

---

### Task 3: 托盘——三态图标、菜单、单实例、关闭到托盘（含 macOS Accessory）

**Files:**
- Create: `deployer/src-tauri/src/tray.rs`（图标纯函数 + 托盘装配）
- Modify: `deployer/src-tauri/src/lib.rs`（注册 single-instance/autostart 插件与 tray 模块）、`deployer/src-tauri/Cargo.toml`（两个插件依赖）、`deployer/src-tauri/capabilities/default.json`（无新增权限需要——core:default 已含窗口控制；autostart 插件权限 `autostart:allow-enable/disable/is-enabled` 加入）

**Interfaces:**
- Consumes: 计划 1 `runner/docker/compose/state` 模块
- Produces:
  - `tray::dot_icon(color: DotColor) -> tauri::image::Image`（32×32 RGBA 实心圆；`DotColor::{Green, Yellow, Red}`）
  - `tray::current_color(status) -> DotColor`：4 容器全 healthy=Green、任一启动中/无健康检查运行=Yellow、任一退出或 Docker 失联=Red
  - 菜单 id 常量：`MENU_OPEN_WEB`/`MENU_OPEN_DASHBOARD`/`MENU_START`/`MENU_STOP`/`MENU_RESTART`/`MENU_TRY_DOCKER`/`MENU_QUIT`
  - `tray::refresh(app: &AppHandle)`：更新图标与菜单项可用态（供 Task 4 轮询调用）

- [ ] **Step 1: 写失败测试（current_color 决策表）**

tray.rs 内联：

```rust
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
        assert_eq!((img.width, img.height), (32, 32));
        assert_eq!(img.rgba.len(), 32 * 32 * 4);
    }
}
```

- [ ] **Step 2: 确认失败**

Run: `cd deployer/src-tauri && cargo test tray`
Expected: 编译失败（模块不存在）。

- [ ] **Step 3: 实现 tray.rs**

```rust
use crate::compose::ContainerStatus;
use crate::runner::CmdSpec;
use crate::runner::CommandRunner;
use std::sync::Arc;
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
```

Cargo.toml：`tauri = { version = "2", features = ["tray-icon"] }`（在既有 tauri 依赖上加 feature）；新增 `tauri-plugin-single-instance = "2"`。
lib.rs run() 改为（autostart 插件 Task 7 接线，此处先只加 single-instance + tray + 关闭到托盘 + macOS Accessory）：

```rust
pub fn run() {
    let data_dir = state::data_dir().expect("无法创建数据目录");
    let persisted = state::load(&data_dir).unwrap_or_else(|_| state::PersistedState::initial());
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            // 二次启动：聚焦既有主窗口而不是新起进程
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }))
        .manage(commands::AppState {
            runner: std::sync::Arc::new(runner::RealRunner),
            data_dir,
            state: std::sync::Mutex::new(persisted),
        })
        .invoke_handler(tauri::generate_handler![/* 计划 1 九命令，逐一保留 */])
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
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

（模块声明补 `mod tray;`；invoke_handler 列表从现 lib.rs 原样保留九项。）

- [ ] **Step 4: 回归 + 手工验证**

Run: `cd deployer/src-tauri && cargo test`
Expected: 32 passed（28 + 4）。
主控手工（记入报告）：`npm run tauri dev` → 托盘出现黄点图标；右键菜单七项齐全；点主窗 X → 窗口隐藏、托盘在；再次 `npm run tauri dev` 起第二实例 → 既有窗口被聚焦（单实例生效，可通过 vite 端口占用报错观察到第二实例退出）。结束后杀进程清理。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri
git commit -m "feat: 托盘三态图标与菜单、单实例、关闭驻托盘（macOS Accessory）"
```

---

### Task 4: 状态轮询 StatusWatcher——30s 引擎探测、掉线通知、「尝试启动 Docker」

**Files:**
- Modify: `deployer/src-tauri/src/tray.rs`（新增 `spawn_watcher`）
- Modify: `deployer/src-tauri/src/lib.rs`（setup 里启动 watcher）
- Test: `deployer/src-tauri/src/tray.rs`（内联，测轮询节奏的纯逻辑）

**Interfaces:**
- Consumes: Task 3 的 `snapshot/current_color/refresh`；计划 1 `docker::start_docker_desktop`
- Produces: `tray::spawn_watcher(app: AppHandle)`（永不退出的 tokio 任务：每 30s `snapshot`→`refresh`；Red 且上一轮非 Red 时 `app.emit("tray://docker-down", ())` 并发系统通知）；事件 `tray://status` 载荷 `{engine_ready: bool, containers: Vec<ContainerStatus>}` 供管理窗口复用；`tray::menu_action` 处理 `MENU_TRY_DOCKER/MENU_START/MENU_STOP/MENU_RESTART`（异步执行 compose start/stop/restart，忙时菜单置灰由前端事件反馈状态）。

- [ ] **Step 1: 写失败测试（状态跃迁通知的纯逻辑）**

tray.rs 追加：

```rust
#[cfg(test)]
mod watcher_tests {
    use super::*;

    #[test]
    fn notify_only_on_fall_to_red() {
        let mut prev = DotColor::Green;
        assert!(should_notify(&mut prev, DotColor::Yellow) == false);
        assert!(should_notify(&mut prev, DotColor::Red) == true);   // 降为红 → 通知
        assert!(should_notify(&mut prev, DotColor::Red) == false);  // 保持红 → 不重复
        assert!(should_notify(&mut prev, DotColor::Green) == false);
        assert!(should_notify(&mut prev, DotColor::Red) == true);   // 再次降红 → 再通知
    }
}
```

```rust
/// 供测试的跃迁判定：只有「非红 → 红」边沿触发通知，并更新 prev
pub fn should_notify(prev: &mut DotColor, now: DotColor) -> bool {
    let edge = *prev != DotColor::Red && now == DotColor::Red;
    *prev = now;
    edge
}
```

- [ ] **Step 2: 确认失败 → Step 3: 实现 watcher 与菜单动作**

```rust
pub fn spawn_watcher(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let mut prev = DotColor::Yellow;
        loop {
            let runner: std::sync::Arc<dyn CommandRunner> = std::sync::Arc::new(crate::runner::RealRunner);
            let dir = state::data_dir().unwrap_or_default();
            let (engine_ready, containers) = snapshot(runner.as_ref(), &dir).await;
            let color = current_color(&containers, engine_ready);
            refresh(&app, color, engine_ready);
            let _ = app.emit("tray://status", StatusPayload { engine_ready, containers: containers.clone() });
            if should_notify(&mut prev, color) {
                let _ = app.emit("tray://docker-down", ());
                use tauri_plugin_notification::NotificationExt; // 若不引插件则删此两行，改为仅事件
            }
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
        }
    });
}

#[derive(Clone, serde::Serialize)]
pub struct StatusPayload {
    pub engine_ready: bool,
    pub containers: Vec<ContainerStatus>,
}
```

（系统通知改用零依赖方案：Tauri 自带 `app.notification()` 需 tauri-plugin-notification；为控依赖，本计划用「托盘 tooltip 变化 + 前端事件 + 管理窗口内醒目红色横幅」承担通知职责——若实现者引 tauri-plugin-notification = "2" 也可，二选一并在报告记录。上例代码取事件-only 版，删除通知两行。）

菜单动作（tray.rs，on_menu_event 的 other 分支改为 spawn 异步处理）：

```rust
            other => {
                let app = app.clone();
                tauri::async_runtime::spawn(async move {
                    let runner: Arc<dyn CommandRunner> = Arc::new(crate::runner::RealRunner);
                    let dir = state::data_dir().unwrap_or_default();
                    let _ = app.emit("tray://menu", other);
                    match other {
                        MENU_TRY_DOCKER => { docker::start_docker_desktop(runner.as_ref()).await; }
                        MENU_START => { compose::start(runner.as_ref(), &dir).await; }
                        MENU_STOP => { compose::stop(runner.as_ref(), &dir).await; }
                        MENU_RESTART => { compose::restart(runner.as_ref(), &dir).await; }
                        _ => {}
                    }
                    // 动作完成立即刷一轮状态，避免最长 30s 延迟
                    let (engine_ready, containers) = snapshot(runner.as_ref(), &dir).await;
                    let color = current_color(&containers, engine_ready);
                    refresh(&app, color, engine_ready);
                    let _ = app.emit("tray://status", StatusPayload { engine_ready, containers });
                });
            }
```

（dead_code 的 compose::start/stop/restart 自此获得消费者，删除对应 allow 注释如有。）
lib.rs setup 末尾：`tray::spawn_watcher(app.handle().clone());`

- [ ] **Step 4: 回归 + 手工**

Run: `cargo test` → 33 passed。
手工：dev 起应用 → 退出 Docker Desktop（托盘右键 Quit）→ ≤30s 图标变红、tooltip 提示未运行；菜单「尝试启动 Docker」→ 图标回绿。

- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer/src-tauri
git commit -m "feat: 托盘 30s 状态轮询、掉线边沿通知与启停/拉起 Docker 菜单动作"
```

---

### Task 5: 管理窗口框架 + 状态总览页（4 容器卡片、10s 轮询、启停按钮）

**Files:**
- Modify: `deployer/src-tauri/tauri.conf.json`（app.windows 增 dashboard 隐藏窗口：`{"label":"dashboard","title":"翻译数据库管理","width":960,"height":680,"visible":false,"resizable":true}`）
- Create: `deployer/src/dashboard/DashboardShell.vue`（el-tabs 四页骨架 + 本任务实现状态页）
- Create: `deployer/src/dashboard/StatusTab.vue`
- Modify: `deployer/src/App.vue`（按 window label 路由）、`deployer/src/api/deployer.ts`（listen 包装与类型导出）
- Test: `deployer/src/dashboard/StatusTab.test.ts`

**Interfaces:**
- Consumes: Task 4 `tray://status` 事件载荷 `StatusPayload{engine_ready, containers}`；`tray://menu` 事件（`open_dashboard` 时 show 窗口）
- Produces: 前端 `useStatusFeed()` composable（`deployer/src/dashboard/useStatusFeed.ts`：订阅 `tray://status` + 每 10s 主动 `invoke('refresh_status')` 兜底）；新 command `refresh_status() -> StatusPayload`、`stack_op(op: "start"|"stop"|"restart") -> Result<(),String>`、`open_dashboard_window()`、`web_url() -> String`（从 state.config.port 计算，部署前返回 http://localhost）

- [ ] **Step 1: 后端三命令（薄封装，无单测，编译+审阅）**

commands.rs 追加：

```rust
#[tauri::command]
pub async fn refresh_status(st: State<'_, AppState>) -> Result<crate::tray::StatusPayload, String> {
    let (engine_ready, containers) = crate::tray::snapshot(st.runner.as_ref(), &st.data_dir).await;
    Ok(crate::tray::StatusPayload { engine_ready, containers })
}

#[tauri::command]
pub async fn stack_op(st: State<'_, AppState>, op: String) -> Result<(), String> {
    let r = st.runner.as_ref();
    let out = match op.as_str() {
        "start" => compose::start(r, &st.data_dir).await,
        "stop" => compose::stop(r, &st.data_dir).await,
        "restart" => compose::restart(r, &st.data_dir).await,
        _ => return Err(format!("未知操作：{op}")),
    };
    if out.success() { Ok(()) } else { Err(format!("操作失败：{}", out.stderr.trim())) }
}

#[tauri::command]
pub async fn open_dashboard_window(app: AppHandle) -> Result<(), String> {
    let w = app.get_webview_window("dashboard").ok_or("管理窗口不存在")?;
    w.show().map_err(|e| e.to_string())?;
    w.set_focus().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn web_url(st: State<'_, AppState>) -> Result<String, String> {
    let port = st.state.lock().unwrap().config.as_ref().map(|c| c.port).unwrap_or(80);
    Ok(deploy_url(port))
}
```

（lib.rs invoke_handler 注册四命令；`MENU_OPEN_WEB` 的 emit 分支同时调用 open_web 逻辑：改为直接 `let _ = crate::commands::open_web_inner(app)`——若引入循环依赖则把 deploy_url+opener 调用抽到独立小函数 `commands::open_web_now(app, port)`，实现者取后者并记录。）

- [ ] **Step 2: 前端路由与状态页（先测后写）**

StatusTab.test.ts：

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StatusTab from './StatusTab.vue'

describe('StatusTab', () => {
  it('渲染 4 容器卡片与健康徽标', () => {
    const wrapper = mount(StatusTab, {
      global: { plugins: [ElementPlus] },
      props: {
        engineReady: true,
        containers: [
          { service: 'frontend', state: 'running', health: null },
          { service: 'backend', state: 'running', health: 'healthy' },
          { service: 'postgres', state: 'running', health: 'healthy' },
          { service: 'elasticsearch', state: 'running', health: 'starting' },
        ],
      },
    })
    expect(wrapper.text()).toContain('frontend')
    expect(wrapper.text()).toContain('elasticsearch')
    expect(wrapper.text()).toContain('healthy')
    expect(wrapper.text()).toContain('starting')
  })
  it('引擎离线时显示红色横幅', () => {
    const wrapper = mount(StatusTab, { global: { plugins: [ElementPlus] } }, )
    // @ts-expect-error 测试缺省 props
    wrapper.setProps({ engineReady: false, containers: [] })
    expect(wrapper.text()).toContain('Docker 未运行')
  })
})
```

（第二条用 setProps 需初值 props；实现时直接在 mount 传 `engineReady: false, containers: []` 断言横幅，模板照此写。）

`StatusTab.vue`：

```vue
<script setup lang="ts">
import { refreshStatus, stackOp } from '../api/deployer'
defineProps<{ engineReady: boolean; containers: { service: string; state: string; health: string | null }[] }>()
const busy = ref('')
async function op(name: 'start' | 'stop' | 'restart') {
  busy.value = name
  try { await stackOp(name) } finally { busy.value = '' }
}
</script>

<template>
  <el-alert v-if="!engineReady" type="error" :closable="false" title="Docker 未运行——请在托盘菜单选择「尝试启动 Docker」" style="margin-bottom: 12px" />
  <el-space wrap>
    <el-card v-for="c in containers" :key="c.service" style="width: 200px" shadow="hover">
      <b>{{ c.service }}</b>
      <p>{{ c.state }} · {{ c.health ?? '—' }}</p>
    </el-card>
  </el-space>
  <div v-if="!containers.length" style="color: #909399; padding: 24px">暂无运行中的容器</div>
  <el-divider />
  <el-button :loading="busy === 'start'" type="primary" plain @click="op('start')">启动</el-button>
  <el-button :loading="busy === 'stop'" @click="op('stop')">停止</el-button>
  <el-button :loading="busy === 'restart'" @click="op('restart')">重启</el-button>
</template>
```

（`ref` 需 import；api/deployer.ts 增 `refreshStatus/stackOp/openDashboardWindow/webUrl` 包装与 `ContainerStatus`/`StatusPayload` 类型。）

`useStatusFeed.ts`：onMounted 订阅 `tray://status` 写 ref，并 `setInterval(10_000, refreshStatus 兜底)`，onUnmounted 清理。

`DashboardShell.vue`：`el-tabs` 四个 tab（状态/日志/设置/维护），本任务先放状态页 + 其余三页占位注释「Task 6/7/8 实现」，数据由 useStatusFeed 提供。

`App.vue`：

```vue
<script setup lang="ts">
import { getCurrentWindow } from '@tauri-apps/api/window'
import { computed } from 'vue'
import WizardShell from './wizard/WizardShell.vue'
import DashboardShell from './dashboard/DashboardShell.vue'
const isDashboard = computed(() => getCurrentWindow().label === 'dashboard')
</script>

<template>
  <main style="padding: 24px">
    <h1>翻译数据库部署器</h1>
    <DashboardShell v-if="isDashboard" />
    <WizardShell v-else />
  </main>
</template>
```

（App.test.ts 已 stub 全部子组件则不受影响；若因新 import 报错，把 DashboardShell 一并 stub。）
向导完成页「打开管理窗口」入口：StepDone.vue 加按钮 `openDashboardWindow()`；托盘 `open_dashboard` 菜单事件由 main 窗口监听 `tray://menu` 调 `openDashboardWindow()`（WizardShell 与 DashboardShell 各自订阅一份幂等处理）。

- [ ] **Step 3: 回归 + 手工**

Run: `cd deployer && npm test`（新增 ≥2 用例全绿）+ `npm run build:front` 零错误；`cd src-tauri && cargo test` 33 passed。
手工：dev → 托盘「打开管理窗口」→ 管理窗口弹出，四卡片（部署过才有数据；未部署显示空态），启停按钮可用。

- [ ] **Step 4: 准备提交（需用户确认）**

```bash
git add deployer
git commit -m "feat: 管理窗口框架与状态总览——4 容器卡片、10s 状态流、整栈启停"
```

---

### Task 6: 日志页——按容器查看最近 500 行 + 手动刷新

**Files:**
- Modify: `deployer/src-tauri/src/commands.rs`、`lib.rs`（注册 `container_logs(service) -> String`）
- Create: `deployer/src/dashboard/LogsTab.vue`
- Modify: `deployer/src/api/deployer.ts`
- Test: `deployer/src/dashboard/LogsTab.test.ts`

**Interfaces:**
- Consumes: 计划 1 `compose::logs(runner, dir, service)`
- Produces: invoke `container_logs{service}` 返回 String（`--no-color --tail 500`）；LogsTab 提供 service 下拉（固定四服务名）+ 查看按钮 + pre 滚动区。

- [ ] **Step 1: 测试先行（组件行为）**

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LogsTab from './LogsTab.vue'

vi.mock('../api/deployer', () => ({ containerLogs: vi.fn(async () => 'line1\nline2') }))

describe('LogsTab', () => {
  it('选择服务并查看后渲染日志行', async () => {
    const wrapper = mount(LogsTab, { global: { plugins: [ElementPlus] } })
    await wrapper.find('button.view').trigger('click')
    expect(wrapper.text()).toContain('line1')
  })
})
```

（vi 需从 vitest import；api mock 置于 import 之后顶部。）

- [ ] **Step 2: 实现命令与组件**

commands.rs：

```rust
#[tauri::command]
pub async fn container_logs(st: State<'_, AppState>, service: String) -> Result<String, String> {
    Ok(compose::logs(st.runner.as_ref(), &st.data_dir, &service).await)
}
```

LogsTab.vue（要点）：`el-select`（options: frontend/backend/postgres/elasticsearch，默认 backend）+ `查看/刷新` 按钮（class view）→ `containerLogs(service)` → `<pre style="max-height: 420px; overflow: auto">{{ logs || '（空）' }}</pre>`；加载中 loading 态。

- [ ] **Step 3: 回归 + 手工**（dev 下查看 backend 500 行 Spring 日志）
- [ ] **Step 4: 准备提交（需用户确认）**

```bash
git add deployer
git commit -m "feat: 管理窗口日志页——按容器最近 500 行手动刷新"
```

---

### Task 7: 设置页 + autostart.rs（工具自启开关、改端口、打开数据目录、Docker Desktop 自启）

**Files:**
- Create: `deployer/src-tauri/src/autostart.rs`
- Modify: `deployer/src-tauri/src/commands.rs`、`lib.rs`（autostart 插件注册与新命令）、`Cargo.toml`（tauri-plugin-autostart）、`capabilities/default.json`（`autostart:default`）
- Create: `deployer/src/dashboard/SettingsTab.vue`
- Modify: `deployer/src/api/deployer.ts`
- Test: `deployer/src-tauri/src/autostart.rs`（内联）、`deployer/src/dashboard/SettingsTab.test.ts`

**Interfaces:**
- Consumes: `compose::materialize_project`、config::render_env、tauri_plugin_autostart（ManagerExt::autolaunch）
- Produces:
  - `autostart::set_docker_autostart(runner, on: bool) -> Result<(), String>`：Windows 写/删 `HKCU\...\Run` 的 `Docker Desktop` 值（`"…\Docker Desktop.exe" -Autostart`，路径复用计划 1 `docker::docker_desktop_path()`）；macOS 改 `~/Library/Group Containers/group.com.docker/settings-store.json` 的 `"openAtLogin"`（serde_json 读写，文件不存在则创建 `{"openAtLogin":true}`），失败 fallback osascript 登录项
  - `autostart::render_run_value(path: &str) -> String`（纯函数：带引号与 -Autostart 参数，可测）
  - commands：`set_tool_autostart{enabled}`（走 autostart 插件）、`get_tool_autostart() -> bool`、`change_port{port}`（校验+TcpListener 预检+改 .env+`up -d frontend` 只重建前端）、`open_data_dir()`（opener 打开 st.data_dir）

- [ ] **Step 1: Rust 测试先行**

autostart.rs：

```rust
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
```

```rust
pub fn render_run_value(path: &str) -> String {
    format!("\"{path}\" -Autostart")
}

pub fn set_macos_open_at_login(file: &std::path::Path, on: bool) -> std::io::Result<()> {
    let mut v: serde_json::Value = std::fs::read_to_string(file)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| serde_json::json!({}));
    v["openAtLogin"] = serde_json::Value::Bool(on);
    std::fs::write(file, serde_json::to_vec_pretty(&v)?)
}
```

`set_docker_autostart`（cfg 分支，macOS shape 校验后恢复）：

```rust
#[cfg(windows)]
pub fn set_docker_autostart(runner: &dyn crate::runner::CommandRunner, on: bool) -> Result<(), String> {
    let _ = runner;
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
        let _ = key.delete_value("Docker Desktop");
        Ok(())
    }
}

#[cfg(target_os = "macos")]
pub fn set_docker_autostart(runner: &dyn crate::runner::CommandRunner, on: bool) -> Result<(), String> {
    let file = dirs::home_dir().ok_or("无 home 目录")?
        .join("Library/Group Containers/group.com.docker/settings-store.json");
    if set_macos_open_at_login(&file, on).is_err() {
        // fallback：登录项
        let script = if on {
            r#"tell application "System Events" to make login item at end with properties {path:"/Applications/Docker.app", hidden:false}"#
        } else {
            r#"tell application "System Events" to delete login item "Docker""#
        };
        let out = runner.run(crate::runner::CmdSpec::new("osascript", &["-e", script])).await;
        // 同步函数内无法 await：签名改为 async（见下）
        todo!("签名 async 化")
    } else { Ok(()) }
}
```

（实现者注意：macOS 分支含 runner await，`set_docker_autostart` 统一改 `pub async fn`，Windows 分支无 await 亦兼容；测试两例不受影响。删除 todo 占位，真实写完 async 版。）

- [ ] **Step 2: commands 四命令**

```rust
#[tauri::command]
pub async fn set_tool_autostart(app: AppHandle, enabled: bool) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    let m = app.autolaunch();
    if enabled { m.enable().map_err(|e| e.to_string()) } else { m.disable().map_err(|e| e.to_string()) }
}

#[tauri::command]
pub async fn get_tool_autostart(app: AppHandle) -> Result<bool, String> {
    use tauri_plugin_autostart::ManagerExt;
    Ok(app.autolaunch().is_enabled().unwrap_or(false))
}

#[tauri::command]
pub async fn change_port(st: State<'_, AppState>, app: AppHandle, port: u16) -> Result<(), String> {
    let cfg = { st.state.lock().unwrap().config.clone().ok_or("尚未部署，无需改端口")? };
    let mut next = cfg.clone();
    next.port = port;
    config::validate(&next).map_err(|e| e.join("；"))?;
    if std::net::TcpListener::bind(("127.0.0.1", port)).is_err() {
        return Err(format!("端口 {port} 已被占用"));
    }
    let env = config::render_env(&next, &config::generate_jwt_secret(), &config::generate_db_password());
    const COMPOSE_YML: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/resources/docker-compose.yml"));
    compose::materialize_project(&st.data_dir, COMPOSE_YML, &env).map_err(|e| e.to_string())?;
    let out = st.runner.run(crate::runner::CmdSpec::new(
        "docker",
        &["compose", "-p", compose::PROJECT_NAME, "--project-directory",
          &st.data_dir.to_string_lossy(), "up", "-d", "frontend"],
    )).await;
    if !out.success() { return Err(format!("重建前端容器失败：{}", out.stderr.trim())); }
    *st.state.lock().unwrap() = state::PersistedState { stage: state::DeployStage::Done, deployed: true, config: Some(next) };
    state::save(&st.data_dir, &st.state.lock().unwrap().clone()).map_err(|e| e.to_string())?;
    let _ = app;
    Ok(())
}
```

（注意：改端口重生成 JWT/DB 密钥会使既有数据卷 DB 密码失配——**必须复用原 .env 中这两项**。修正实现：从 `st.data_dir.join(".env")` 读回 `TRANSDB_JWT_SECRET/TRANSDB_DB_PASSWORD/TRANSDB_ADMIN_PASSWORD` 三行再 render_env 传入，新密码只换端口；实现者按此写并在报告记录。上面代码中 `let env = …` 行替换为读回逻辑：）

```rust
    let old = std::fs::read_to_string(st.data_dir.join(".env")).map_err(|e| e.to_string())?;
    let find = |k: &str| old.lines().find(|l| l.starts_with(k)).and_then(|l| l.split_once('=').map(|(_, v)| v.to_string())).unwrap_or_default();
    let env = config::render_env(&next, &find("TRANSDB_JWT_SECRET="), &find("TRANSDB_DB_PASSWORD="));
```

（`render_env(cfg, jwt, db_pw)` 参数序照计划 1 签名；admin_password 由 next 携带原值——但 state.json 已剥密码为空串，故 `find("TRANSDB_ADMIN_PASSWORD=")` 回填 `next.admin_password` 后再 render。）

`open_data_dir`：

```rust
#[tauri::command]
pub async fn open_data_dir(app: AppHandle, st: State<'_, AppState>) -> Result<(), String> {
    app.opener().open_path(st.data_dir.to_string_lossy().to_string(), None::<&str>).map_err(|e| e.to_string())
}
```

lib.rs：`.plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, None))`；capabilities 加 `"autostart:default"`。

- [ ] **Step 3: SettingsTab.vue（测先行：显示当前自启开关状态并触发 set_tool_autostart）**

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SettingsTab from './SettingsTab.vue'

vi.mock('../api/deployer', () => ({
  getToolAutostart: vi.fn(async () => true),
  setToolAutostart: vi.fn(async () => {}),
  changePort: vi.fn(async () => {}),
  openDataDir: vi.fn(async () => {}),
}))

describe('SettingsTab', () => {
  it('回显工具自启开启状态', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('.tool-autostart input').exists()).toBe(true)
  })
})
```

（flushPromises 从 vitest 导入。）组件含：`el-switch`（class tool-autostart，change→setToolAutostart）、端口 `el-input-number` + 「应用端口」按钮（changePort，成功 ElMessage）、`el-button`「打开数据目录」（openDataDir）、说明文字（数据卷 pgdata/esdata 由 Docker 管理说明）。

- [ ] **Step 4: 回归 + 手工**（cargo 35+/npm 全绿；手工：开关自启后查 Run 键/登录项、改端口 8080 → up -d frontend 后 http://localhost:8080 可访问，改回 80）
- [ ] **Step 5: 准备提交（需用户确认）**

```bash
git add deployer
git commit -m "feat: 管理窗口设置页与三层自启——改端口只重建前端、工具自启开关、Docker Desktop 自启"
```

---

### Task 8: 维护页——升级（pull+up）与两步卸载（down / down -v）

**Files:**
- Modify: `deployer/src-tauri/src/commands.rs`、`lib.rs`（`upgrade_stack`、`uninstall(remove_data: bool)`）
- Create: `deployer/src/dashboard/MaintainTab.vue`
- Modify: `deployer/src/api/deployer.ts`
- Test: `deployer/src/dashboard/MaintainTab.test.ts`

**Interfaces:**
- Consumes: `compose::{pull, up_wait, down}`；`autostart::set_docker_autostart(runner, on)`（T7 交付，此前无调用方）
- Produces: `upgrade_stack() -> Result<(),String>`（pull→up_wait，进度事件复用 `deploy://progress`）；`uninstall{removeData}`（down[-v]；removeData=true 且成功后 state 重置为 initial 并落盘；前端两步确认）；**另：`start_deploy` 成功后 spawn 异步 `set_docker_autostart(runner, true)`（fire-and-forget，规格三层自启的 Docker 层接线，失败仅忽略——发版冒烟覆盖）**。

- [ ] **Step 0（主控追加）: start_deploy 成功路径接线 Docker 层自启**

commands.rs 的 start_deploy 在 `deployed=true` 落盘成功后追加：

```rust
    // 规格三层自启的 Docker 层：部署成功即开启 Docker Desktop 登录自启（fire-and-forget，失败不影响部署结果）
    let runner_for_autostart = st.runner.clone();
    tauri::async_runtime::spawn(async move {
        let _ = autostart::set_docker_autostart(runner_for_autostart.as_ref(), true).await;
    });
```

（AppState.runner 为 `Arc<dyn CommandRunner>`，clone 语义共享。）

- [ ] **Step 1: 命令**

```rust
#[tauri::command]
pub async fn upgrade_stack(app: AppHandle, st: State<'_, AppState>) -> Result<(), String> {
    emit(&app, ProgressEvent { stage: "pull".into(), message: "拉取镜像更新…".into(), pull: None, download: None });
    let app_h = app.clone();
    let out = compose::pull(st.runner.as_ref(), &st.data_dir, Box::new(move |l| {
        if let Some(ev) = compose::parse_pull_line(l) {
            emit(&app_h, ProgressEvent { stage: "pull".into(), message: format!("{} {}", ev.service, ev.phase), pull: Some(ev), download: None });
        }
    })).await;
    if !out.success() { return Err(format!("拉取失败：{}", out.stderr.trim())); }
    let app_h = app.clone();
    let up = compose::up_wait(st.runner.as_ref(), &st.data_dir, Box::new(move |l| {
        emit(&app_h, ProgressEvent { stage: "up".into(), message: l.to_string(), pull: None, download: None });
    })).await;
    if up.success() { Ok(()) } else { Err(format!("重建失败：{}", up.stderr.trim())) }
}

#[tauri::command]
pub async fn uninstall(st: State<'_, AppState>, remove_data: bool) -> Result<(), String> {
    let out = compose::down(st.runner.as_ref(), &st.data_dir, remove_data).await;
    if !out.success() { return Err(format!("卸载失败：{}", out.stderr.trim())); }
    if remove_data {
        let fresh = state::PersistedState::initial();
        state::save(&st.data_dir, &fresh).map_err(|e| e.to_string())?;
        *st.state.lock().unwrap() = fresh;
    }
    Ok(())
}
```

- [ ] **Step 2: MaintainTab（测先行：卸载需两步确认，勾选删除数据才传 removeData=true）**

组件要点：升级按钮（loading，订阅 progress 显示一行进度）；「卸载」→ `ElMessageBox.confirm('将停止并删除容器，数据卷保留。继续？')` → 若用户在第二步 dialog 勾选 `el-checkbox`「同时删除全部数据（不可恢复）」则 `uninstall(true)` 否则 `uninstall(false)`；完成后 ElMessage 提示并说明数据目录位置。测试：mock api 后 mount，点击卸载按钮 → 断言 ElMessageBox 被调用（mock element-plus 的 ElMessageBox）。

- [ ] **Step 3: 回归 + 手工**（真机：升级按钮跑一轮 pull+up；卸载（保留数据）后容器消失、再次启动应用向导回到初始态可重部署——数据卷复用验证 admin 密码仍有效）
- [ ] **Step 4: 准备提交（需用户确认）**

```bash
git add deployer
git commit -m "feat: 管理窗口维护页——镜像升级与两步卸载（可选删数据卷）"
```

---

### Task 9: 规格缺口包——备选端口推荐、下载断点续传、失败知识库、完成页展示密码

**Files:**
- Modify: `deployer/src-tauri/src/commands.rs`（EnvReport 增 `suggested_port`；install_error_hint 扩充为知识库）
- Modify: `deployer/src-tauri/src/docker.rs`（download_file 断点续传）
- Modify: `deployer/src/wizard/StepEnvCheck.vue`、`StepDocker.vue`（显示建议端口/知识库提示）、`StepDone.vue`（展示密码+复制）、`types.ts`
- Test: `commands.rs` 内联（suggested_port/knowledge base）、`docker.rs` 内联（Range 头构造纯函数）、`StepDone.test.ts`

**Interfaces:**
- Consumes: EnvReport/download_file/install_error_hint
- Produces: `EnvReport{suggested_port: u16}`（port 被占时为 8080..8090 首个空闲，否则等于 port）；`docker::range_header(existing_len: u64) -> String`（`bytes={len}-`）；知识库 `commands::failure_hint(msg: &str) -> Option<&'static str>`（匹配 port already allocated / out of memory / no space left / i/o timeout / context deadline exceeded → 中文建议）；StepDone 显示 `admin / 所设密码` 与复制按钮（navigator.clipboard.writeText + ElMessage）。

- [ ] **Step 1: Rust 测试**

```rust
    #[test]
    fn range_header_resumes_from_existing_length() {
        assert_eq!(range_header(1024), "bytes=1024-");
        assert_eq!(range_header(0), "bytes=0-");
    }

    #[test]
    fn failure_hint_maps_common_docker_errors() {
        assert!(failure_hint("Bind for 0.0.0.0:80 failed: port is already allocated").is_some());
        assert!(failure_hint("no space left on device").unwrap().contains("磁盘"));
        assert!(failure_hint("i/o timeout").unwrap().contains("网络"));
        assert!(failure_hint("something else").is_none());
    }
```

- [ ] **Step 2: 实现**

- docker.rs download_file：dest 存在时先取 `tokio::fs::metadata(...).len()` 作为 `existing`，请求头 `If-Range`/`Range`：

```rust
    let existing = tokio::fs::metadata(dest).await.map(|m| m.len()).unwrap_or(0);
    let client = reqwest::Client::new();
    let mut req = client.get(url);
    if existing > 0 {
        req = req.header("Range", range_header(existing));
    }
    let resp = req.send().await.map_err(|e| format!("下载失败：{e}"))?;
    // 206 → 追加模式续传；200（服务器不支持 Range）→ 从头覆盖
    let resume = resp.status().as_u16() == 206;
    let resp = resp.error_for_status().map_err(|e| format!("下载失败：{e}"))?;
    let mut file = if resume {
        let mut f = tokio::fs::OpenOptions::new().append(true).open(dest).await.map_err(|e| format!("打开续传文件失败：{e}"))?;
        downloaded = existing;
        f
    } else {
        tokio::fs::File::create(dest).await.map_err(|e| format!("创建文件失败：{e}"))?
    };
```

（`downloaded` 变量起始值随 resume 调整；on_progress 里 total 若为 0 则百分比隐藏——前端 F5 已有守卫。）

- commands.rs check_env 追加 suggested_port 计算（bind 探测 8080..=8090 取首个可绑端口；全部被占则回 port 本身）；failure_hint 与 install_error_hint 的 else 分支串联（ensure_docker/start_deploy 的 Err 文案附 `\n建议：{hint}`）。
- 前端：StepEnvCheck 端口被占时展示「建议改用 {suggested_port}」；StepDone 密码区 `admin / {{password}}`——但 state.json 已剥密码，**完成页密码来自当次会话**：StepDeploy 完成时把 config.admin_password 经 props 链传给 StepDone（WizardShell 已持有 submit 时的 cfg，保存在 `ref lastConfig`；restart 后无密码则显示「（已隐去，本次会话未设置）」）。复制按钮调 navigator.clipboard。

- [ ] **Step 3: 回归 + 手工**（cargo/npm 全绿；手工：占住 80 重启向导看建议端口；下载中断续传难真机模拟，以单元覆盖 range_header + 代码审阅）
- [ ] **Step 4: 准备提交（需用户确认）**

```bash
git add deployer
git commit -m "feat: 端口占用自动推荐、下载断点续传、失败知识库、完成页展示与复制密码"
```

---

### Task 10: CI——三平台构建、资源一致性校验、tag 发布

**Files:**
- Create: `.github/workflows/deployer-release.yml`
- Test: 工作流 YAML（无单测；以 actionlint 思路人工校验 + 首次打 tag 实跑验证，冒烟留发版清单）

**Interfaces:**
- Consumes: deployer 构建链（beforeBuildCommand 已含 compose 同步）
- Produces: push tag `deployer-v*` → 三个 job（windows-latest / macos-13 / macos-14）构建 Tauri bundle（NSIS exe / dmg），资源一致性校验步骤，产物上传 Release。

- [ ] **Step 1: 工作流**

```yaml
name: deployer-release
on:
  push:
    tags: ['deployer-v*']
  workflow_dispatch:

jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: windows-latest
            artifact: windows-installer
          - os: macos-13      # Intel
            artifact: macos-intel-dmg
          - os: macos-14      # Apple Silicon
            artifact: macos-arm64-dmg
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: npm, cache-dependency-path: deployer/package-lock.json }
      - uses: dtolnay/rust-toolchain@stable
      - uses: swatinem/rust-cache@v2
        with: { workspaces: deployer/src-tauri }
      - name: 资源一致性校验（内嵌 compose 必须与根文件一致）
        shell: bash
        run: |
          cd deployer && node scripts/sync-compose.mjs
          git diff --exit-code -- src-tauri/resources/docker-compose.yml || \
            (echo "::error::resources/docker-compose.yml 与根 docker-compose.yml 不一致，请先运行同步脚本提交" && exit 1)
      - name: 安装依赖
        run: npm ci
        working-directory: deployer
      - name: 单元测试
        run: npm test
        working-directory: deployer
      - uses: tauri-apps/tauri-action@v0
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        with:
          projectPath: deployer
          tagName: ${{ github.ref_name }}
          releaseName: '部署器 ${{ github.ref_name }}'
          releaseDraft: true
          releaseBody: '零基础用户一键部署包。Windows 首次运行如遇 SmartScreen 请选「仍要运行」；macOS 首次请右键→打开。'
```

（Rust 单测并入 npm test 步骤之外另加一步 `cargo test`，working-directory: deployer/src-tauri，仅 Linux 可省——按矩阵全跑，注意 Windows runner 的 cargo test 需 Docker 的用例只有 FakeRunner 注入，全部可跑。）

- [ ] **Step 2: 本地静态自查**

Run: `git ls-files .github` 确认路径；YAML 语法用 `node -e "console.log(require('js-yaml').load(require('fs').readFileSync('.github/workflows/deployer-release.yml','utf8')))"`（无 js-yaml 则以审读代替）。
Expected: 语法通过、matrix 三平台、校验步骤先于构建。

- [ ] **Step 3: 准备提交（需用户确认）**

```bash
git add .github/workflows/deployer-release.yml
git commit -m "ci: 部署器三平台构建与 tag 发布——资源一致性校验前置，产物挂 GitHub Releases（草稿）"
```

---

### Task 11: README「一键部署」章节 + 发版冒烟清单

**Files:**
- Modify: `README.md`（顶部增「一键部署（推荐零基础用户）」章节）
- Modify: `deployer/README.md`（发版冒烟清单）

**Interfaces:**
- Consumes: Task 10 的 Release 产物
- Produces: 面向零基础用户的下载/安装说明（含 SmartScreen/右键打开图文文字版）、`deployer/README.md` 发版冒烟清单。

- [ ] **Step 1: README 章节内容**

```markdown
## 一键部署（推荐零基础用户）

从 [GitHub Releases](../../releases) 下载对应系统的安装包（Windows 选 `.exe`，Mac 按 CPU 选 `.dmg`），
双击安装后打开「翻译数据库部署器」，按向导操作：

1. 环境检测自动完成（无需操作）
2. 未装 Docker 时自动下载安装（Windows 会弹系统确认框，可能要求重启电脑；Mac 需在 Docker 弹窗点一次「接受」）
3. 设置管理员密码（≥8 位，不能包含空格、#、$ 或引号），端口保持默认 80 即可
4. 等待镜像下载与启动（约 5-15 分钟），完成后点「打开网页」登录（账号 admin）

开机后系统自动恢复，无需手动操作；托盘图标绿色=正常。日常启停/日志/升级/卸载都在托盘的「管理窗口」。

> Windows 首次运行如遇 SmartScreen 蓝色警告：点「更多信息」→「仍要运行」。
> macOS 首次打开提示无法验证开发者：右键安装包→「打开」。原因见设计文档「已知限制：无代码签名」。
```

（置于现有「Docker 一键部署」章节之前，原章节标题改为「## Docker Compose 部署（进阶）」。）

- [ ] **Step 2: deployer/README.md 追加发版冒烟清单**

```markdown
## 发版冒烟清单（每个 Release 前，逐台执行）

- [ ] Windows 11 干净虚拟机：无 Docker 状态下全流程（安装器→UAC→部署→登录→重启机器自启→改端口→升级→卸载保留数据→卸载删数据）
- [ ] Windows 10 22H2（含家庭版）同上精简版（部署→重启自启→登录）
- [ ] macOS Apple Silicon：DMG 拖入 Applications→条款接受→全流程→重启自启
- [ ] macOS Intel：同上精简版
- [ ] 已装 Docker 的机器：直接部署不触发安装
- [ ] 断网中途重试：下载与拉取均可恢复
```

- [ ] **Step 3: 准备提交（需用户确认）**

```bash
git add README.md deployer/README.md
git commit -m "docs: README 一键部署用户章节与发版冒烟清单"
```

---

## Self-Review 记录

- **Spec 覆盖**：§5 托盘（T3/T4）、管理窗口四页（T5-T8）、三层自启（T7 + 计划 1 已做的容器层）、§7 CI/发布/README（T10/T11）、终审必修（T1/T2）、规格缺口四项（T9）均已对应；「忘记 admin 密码」在 T8 卸载路径文案中重申。
- **占位符扫描**：Task 7 macOS 分支的 `todo!` 是计划内标注的签名 async 化提示，实现者须删除占位真实完成（已在该任务正文写明）；无其他 TBD。
- **类型一致性**：`StatusPayload{engine_ready, containers}` 在 T4（Rust 定义）与 T5（TS 消费）一致；`DotColor/current_color/refresh` 在 T3 定义 T4 消费；`container_logs{service}/stack_op{op}/change_port{port}/uninstall{removeData}` 命令名与前端包装一一对应（驼峰转 snake 由 Tauri 自动处理，前端调用用 camelCase 参数名）。
- **已知风险**：change_port 复用旧 .env 密钥的回填逻辑是数据安全关键点（重生成密钥会导致 DB 失配），任务内已写明实现方式并要求报告记录；macOS 代码仍只能 shape 验证，发版冒烟兜底。
