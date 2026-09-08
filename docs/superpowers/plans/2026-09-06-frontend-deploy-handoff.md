# Plan 4（前端与部署）收尾记录与项目总结

- 日期：2026-09-08
- 交付：`3c1926e..5354da4`（9 个提交），全部推送至 Gitee `main`
- 测试：后端 106/106、前端 vitest 6/6、vue-tsc + vite build 绿；compose 端到端冒烟全过（登录/录入/ES 搜索非降级/SPA）
- 终审：首次判定 Needs fixes（XSS + healthcheck 两个 Important + 2 个 (a) 项）→ 修复波 → 复审 4/4 ADDRESSED → Ready

## 一、项目全景（4 个里程碑全部完成）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| Plan 1 后端核心 | PG/领域模型/JWT 三角色/条目标签用户 CRUD（40 测试） | ✅ d53b7ed |
| Plan 2 搜索引擎 | ES IK+pinyin/分词拼音容错双向搜索/同步对账重建/降级熔断（+68） | ✅ c476662 |
| Plan 3 批量导入 | JSON/CSV/Excel 两阶段导入/重复策略/批量 ES 同步（+38） | ✅ 3c1926e |
| Plan 4 前端+部署 | Vue3 全部页面/Docker Compose 四容器/JWT fail-fast/XSS 加固 | ✅ 5354da4 |

`docker compose up -d`（.env 三必填）→ http://localhost 即可用：admin 登录、中文分词/拼音/英文容错搜索、录入/编辑/导入语料、管理后台。

## 二、Plan 4 终审裁定与安全加固

1. **ES 高亮 XSS 修复**：highlight 请求增加 `encoder: html`——ES 转义用户文本、保留服务端 `<em>`，配合前端"仅高亮片段 v-html"纪律，闭合存储型 XSS（有判别性测试）
2. **backend 容器 healthcheck**：actuator /health + curl（镜像内已验证存在）+ frontend `depends_on: service_healthy`（满足规格 §12）
3. **构建加固**：`npm ci`（禁止 lockfile 漂移静默回退）+ `frontend/.dockerignore`
4. **JWT fail-fast**：compose 设 `TRANSDB_PROFILE=prod`，内置 dev 密钥直接拒绝启动；`${VAR:?}` 必填守卫为第二道防线

## 三、遗留清单（backlog，按优先级）

**部署/运维**
- compose healthcheck 探针未在真实 up 中端到端观察（下次部署确认 backend 到 healthy）
- tinypinyin 2.0.3 不在 Maven Central → Docker 构建依赖 aliyun 镜像（backend/mvn-docker-settings.xml）；可换 `io.github.biezhi:TinyPinyin:2.0.3.RELEASE`（Central 有）后删镜像设置
- ES 换别名后旧索引删除部分失败会留孤儿索引；重试队列/对账可观测性钩子

**性能（50 万条冲刺时）**
- 导入 executeAndReturnKey 逐行 → JDBC returning 真批
- 前端 1.05MB 主 chunk（Element Plus 全量引入 → 按需/手动 chunk）
- 对账逐文档 ES GET → _mget 批量

**前端 polish**
- Awaitility abort-on-RuntimeException 陷阱（统一 JsonPath 读取 helper）
- suggestTimer 卸载清理/外点关闭、删除按钮 try/catch、dup-tag 5002 自动回选、redirect 白名单（`/` 开头）、用户表分页、浏览器 UI 冒烟补做（curl 已覆盖同等链路）

**后端已知限制（设计取舍，记录在案）**
- 被禁用用户 token 过期前（≤24h）仍可访问（无状态 JWT；可选 filter 查库校验）
- 预览会话内存态（单实例假设；多实例需外置存储）
- suggest/降级 facets 未过滤草稿衍生名称（MVP 接受）

## 四、项目文档索引

- 设计规格：`docs/superpowers/specs/2026-09-06-translation-database-design.md`（§4.2 suggest 行已修订）
- 四份实施计划与三份收尾备忘：`docs/superpowers/plans/`
- README：本地开发、Docker 一键部署、API 一览、安全清单
