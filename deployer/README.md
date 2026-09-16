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
