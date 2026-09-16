# 一键部署器（transdb-deployer）设计文档

- 日期：2026-09-16
- 状态：设计已确认，待实施
- 阶段：部署体验改造，基于现有 docker compose 部署方案（README「Docker 一键部署」章节）

## 1. 背景与目标

现有部署要求用户：自行安装 Docker Desktop → 复制 `.env.example` → 手工填写 3 个必填密钥/密码 → 执行 `docker compose up -d`。对零基础用户门槛过高：找不到 Docker 安装包、不知道怎么生成 JWT 密钥、看不懂命令行报错。

本项目开发一个跨平台桌面程序，把上述全部过程收敛为「双击安装包 → 向导点下一步 → 浏览器自动可用」。

**需求（用户已确认）：**

- 至少支持 Windows 与 macOS；一键单机部署（单机一套实例）
- 部署过程可设置：网页访问端口、系统管理员账号密码
- 开机自启：重启机器后系统无需人工干预即可访问
- 自动下载并静默安装 Docker Desktop（用户只需面对系统级确认框：Windows UAC、macOS 授权与 Docker 服务条款接受）
- 工具形态：**向导 + 托盘常驻管理**（部署完成后驻留托盘，提供启停/状态/日志/升级/卸载）
- 技术栈：**Tauri 2 + Vue 3 + Element Plus**（Rust 侧负责托盘/自启/子进程）
- 仅在线部署（镜像从 Docker Hub 拉，Docker 安装包从官方源下）；不做离线包
- 系统范围：Docker Desktop 官方支持范围——Windows 10 22H2+/Windows 11（64 位，含家庭版，WSL2 后端）+ macOS 近三年大版本（Intel 与 Apple Silicon）；硬件下限：内存 ≥ 8GB、磁盘空余 ≥ 15GB
- 管理员账号：**用户名固定 `admin`，仅密码可设**（后端零改动）
- 向导配置项：默认两项（网页端口、admin 密码）+ 折叠的「高级选项」（ES 堆内存、后端 JVM 内存、镜像版本）；JWT 密钥与数据库密码由工具自动生成强随机值

**成功标准：**

- 零基础用户在 Win10+/macOS 机器上双击安装包，向导中除设置 admin 密码外全程默认，15 分钟内（受带宽影响，镜像约 1.7GB）浏览器打开网页并用 admin + 所设密码登录成功
- 重启机器后，网页无需任何人工干预仍可访问
- 部署中途断电/断网/杀进程，重新打开工具能从断点续跑

**范围外（首版明确不做）：** 离线部署包、日志实时 follow、admin 密码自助重置、Linux 支持、一台机器多实例、工具自身自动更新、代码签名。

## 2. 关键决策记录

**与 Docker 的交互：子进程调用 `docker compose` CLI（方案 A，用户在三个候选中选定）。** 工具自带 compose 文件，部署时在应用数据目录生成 `.env`，以子进程运行 `docker compose -p transdb up -d --wait / ps / logs / pull / down` 等命令，进度经 `--progress=plain` 输出流式解析回 UI。落选方案：

- *Rust 直连 Docker Engine API（bollard）*：需重新实现 compose 语义（网络/depends_on 健康等待/重启策略/卷/拉取进度），工作量数倍且全是新 bug 来源
- *特权管理容器代理*：多一层间接与一个待维护镜像，过度设计

选 CLI 方案的理由：编排逻辑留给 Docker 自己，`depends_on` + healthcheck 启动顺序、镜像拉取、卷管理全部照搬现有 compose 行为，与现有部署路径一致、可回归验证；Docker Desktop 必装 docker CLI，依赖天然满足。

**其他已确认决策：**

- 工具形态为向导 + 托盘常驻（而非一次性安装器），托盘提供启停/状态/日志/升级/卸载
- 自动安装 Docker Desktop：Windows 静默安装（`install --quiet --accept-license`）；macOS 下载 DMG → 拷贝 Docker.app → 首启需用户在 Docker 官方弹窗点一次「接受」条款（官方强制人工步骤，工具明示引导）
- 开机自启三层：容器 `restart: unless-stopped` + Docker Desktop 登录自启 + 工具自身登录自启（默认开，托盘可关）
- compose 文件单一来源：根 `docker-compose.yml`（构建时打包为 Tauri 资源），不维护副本
- compose 项目名固定 `transdb`（`-p transdb`），数据卷 `pgdata`/`esdata` 归 Docker 管理；卸载工具不影响数据，删数据必须显式勾选并二次确认

## 3. 总体架构与工程结构

一个 Tauri 2 桌面程序，双形态共用一份代码：

- **向导模式**：首启/未部署时全屏向导窗口
- **常驻模式**：部署完成后驻留系统托盘 + 管理窗口

代码位于本仓库新增 `deployer/` 目录（与 `backend/`、`frontend/` 平级）：

```
deployer/
├── src/                  # Vue 3 + Element Plus（向导页 + 管理窗口）
├── src-tauri/
│   ├── src/
│   │   ├── main.rs
│   │   ├── docker.rs     # Docker 检测、Docker Desktop 下载安装、引擎就绪等待
│   │   ├── compose.rs    # docker compose 子进程封装（up/ps/logs/pull/down），进度流回传
│   │   ├── config.rs     # 向导输入 → .env 生成、随机密钥、state.json 读写
│   │   ├── autostart.rs  # Docker Desktop 开机自启 + 工具自身自启
│   │   ├── tray.rs       # 托盘菜单与窗口管理
│   │   └── runner.rs     # 子进程抽象 trait（测试可注入假实现）
│   └── tauri.conf.json
└── package.json / vite.config.ts
```

**对现有代码的改动**（均有默认值，对现有 CLI 用户无影响）：

1. 根 `docker-compose.yml`：4 个服务补 `restart: unless-stopped`（开机自启前提）
2. 根 `docker-compose.yml`：backend/frontend 镜像 tag 参数化——`swdrts/transdb-backend:${TRANSDB_APP_VERSION:-0.1.0}`、`swdrts/transdb-frontend:${TRANSDB_APP_VERSION:-0.1.0}`（高级选项「镜像版本」写入 `.env` 即可切换）；ES 镜像随 compose 固定，不暴露

**应用数据落盘**（部署时生成，重装工具不影响）：

| 系统 | 目录 |
|---|---|
| Windows | `%APPDATA%\transdb\` |
| macOS | `~/Library/Application Support/transdb/` |

内含：

- `docker-compose.yml`——从打包资源拷贝
- `.env`——端口、admin 密码、自动生成的 JWT 密钥（≥32 字节随机）/数据库密码、高级选项
- `state.json`——部署阶段状态机与部署结果（端口等），断点续跑依据

## 4. 部署向导流程

五步，阶段持久化到 `state.json`，中断后重开工具从断点续跑：

```
① 环境检测 → ② Docker 准备(条件步) → ③ 配置 → ④ 部署执行 → ⑤ 完成
```

**① 环境检测**：OS 版本；内存 ≥8GB（不足仅警告、允许继续）；磁盘空余 ≥15GB；网络可达（Docker Hub / desktop.docker.com）；端口占用预检（默认 80 被占用时自动推荐备选端口）。

**② Docker 准备**（`docker info` 成功则跳过；已装但引擎未运行则直接拉起 Docker Desktop 并等待）：

- Windows：下载官方安装包（进度条、断点续传）→ 静默安装 `install --quiet --accept-license`（弹一次 UAC，可能要求重启）→ 重启后重开工具自动续跑 → 轮询 `docker info` 等引擎就绪（首启约 30–60 秒）。若失败于缺 WSL2：展示「一键安装 WSL2」按钮（执行 `wsl --install`，需管理员）。
- macOS：按 CPU 架构下载对应 DMG → 挂载并拷贝 `Docker.app` 到 `/Applications` → 首次启动 Docker Desktop，用户在官方弹窗点一次「接受」服务条款 → 等引擎就绪。

**③ 配置**：两项 + 折叠高级选项（见第 1 节）；admin 密码至少 8 位；JWT 密钥与数据库密码自动生成。

**④ 部署执行**（进度页实时流式输出）：

1. 落盘 `docker-compose.yml` + `.env`
2. `docker compose -p transdb pull`（逐镜像进度）
3. `docker compose -p transdb up -d --wait`（现有 healthcheck 依赖链：PG/ES → backend → frontend）
4. 失败：展示错误摘要 + 常见原因知识库（端口冲突/内存不足/磁盘不足/网络超时）+「重试本步」

**⑤ 完成**：展示访问地址 `http://localhost:<端口>`、账号 `admin` + 所设密码（提醒抄录）、「打开网页」按钮；转入托盘常驻模式。

**开机自启三层**（完成步自动设置）：

1. 容器：compose `restart: unless-stopped`
2. Docker Desktop：Windows 写 Run 注册表键；macOS 写 settings 的 `openAtLogin`（失败退回将 Docker.app 加入登录项）
3. 工具自身：tauri-plugin-autostart，默认开启，托盘菜单可关

## 5. 托盘常驻管理

托盘图标状态色：绿=全部容器健康、黄=启动中、红=异常/Docker 未运行。左键开管理窗口，右键菜单：

```
打开翻译数据库（浏览器）        ← 最高频动作
打开管理窗口
──────────────
启动 / 停止 / 重启（整栈）
退出工具（容器继续运行）
```

管理窗口四个标签页：

1. **状态总览**：4 容器卡片（frontend/backend/postgres/elasticsearch），状态徽标 + 健康详情；`docker compose ps --format json`，10 秒轮询 + 打开即刷新
2. **日志**：按容器查看最近 500 行（`docker compose logs --tail`）+ 刷新按钮；不做实时 follow
3. **设置**：修改网页端口（预检占用 → 改 `.env` → `up -d frontend`，只重建前端容器）；工具自启开关；「打开数据目录」+ 数据备份位置说明
4. **维护**：升级（`docker compose pull && up -d`，按 `.env` 镜像版本）；卸载（两步确认，默认 `down` 保留数据卷，勾选「同时删除全部数据」才 `down -v`）

**运行期错误处理：**

- Docker 引擎掉线：托盘每 30 秒轮询 `docker info`，失联时图标变红 + 通知，菜单出现「尝试启动 Docker」
- 忘记 admin 密码：后端暂无自助重置接口，工具如实告知唯一途径是卸载并清数据重部署（数据丢失），列为已知限制
- 工具重装：数据目录与 Docker 卷不受影响，自动识别已有部署直接进托盘模式

## 6. 测试策略

1. **Rust 单元测试**（`cargo test`，不依赖真实 Docker，子进程经 `runner.rs` trait 注入假实现）：`.env` 生成与随机密钥规格、端口/高级选项校验、compose 命令构造、`ps --format json` 与 `pull --progress=plain` 输出解析、状态机断点续跑
2. **Vue 组件测试**（`vitest`，mock tauri invoke）：向导各步表单校验、进度流渲染、管理窗口状态卡片
3. **手工冒烟清单**（发版前）：
   - Windows 10 / 11（含家庭版）与 macOS（Intel + Apple Silicon）各一台干净机器：装 Docker → 部署 → 重启验证自启 → 改端口 → 升级 → 卸载（保留数据/删数据两条路径）
   - 已装 Docker 的机器直接部署；部署中断网/杀进程后重开工具续跑

## 7. 构建与发布

- GitHub Actions 矩阵：`windows-latest`（NSIS .exe）、`macos-13`（Intel .dmg）、`macos-14`（Apple Silicon .dmg）；不做 universal 包
- 产物发布到 GitHub Releases；README 增补「一键部署」章节作为普通用户入口（含无签名的 SmartScreen/右键打开图文说明）
- CI 校验工具内嵌 compose 与根 `docker-compose.yml` 一致（构建脚本拷贝 + diff）

## 8. 已知限制

- 无代码签名：Windows 触发 SmartScreen 警告（「仍要运行」），macOS 首次打开需右键 → 打开；README 图文说明
- 仅在线部署；admin 密码无自助重置；日志不实时 follow；不支持 Linux 与多实例；工具自身无自动更新（重新下载覆盖安装，数据与部署状态不丢）
