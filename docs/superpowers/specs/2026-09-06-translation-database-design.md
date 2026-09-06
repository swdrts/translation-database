# 翻译学术数据库（Translation Academic Database）设计文档

- 日期：2026-09-06
- 状态：已获用户批准的设计稿
- 阶段：首期（MVP+），面向中文古典文献汉译英场景

## 1. 背景与目标

构建一个翻译学术数据库，用于存储、检索古典中文作品及其英译对照。用户可逐条录入"古文原文 + 英文译文"句段，打标签、标注出处元数据，并通过高效搜索（标签筛选、中文分词全文检索、拼音/首字母、英文容错）快速定位条目。

**成功标准：**

- 50 万条句段规模下，常见搜索（分词全文、拼音、标签筛选）响应 < 1s（P95）
- 支持 Docker 一键部署（`docker compose up -d`），宿主机内存 ≥ 8GB
- 支持批量导入已有语料（JSON/CSV/Excel），导入 10 万条可在分钟级完成

**范围外（首期明确不做）：** 开放注册、整篇文档自动切分导入、数据导出、刷新 token、国际化（i18n）、ES 集群（单节点即可）。

## 2. 总体架构

四个容器，模块化单体后端：

```
浏览器 → [frontend: Nginx] ──静态文件──→ Vue 3 SPA
                │ /api 反向代理
                ▼
        [backend: Spring Boot 3] ──写──→ [postgres]  ←权威数据
                │ 异步同步 / 搜索查询
                ▼
        [elasticsearch 8]  ←搜索索引（IK 中文分词 + pinyin 插件）
```

| 组件 | 技术选型 | 说明 |
|---|---|---|
| 后端 | Spring Boot 3.3.x + Java 21 + Maven | 包结构：controller / service / repository / search / security / domain / dto / config |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router | SPA，Nginx 托管并反代 `/api` |
| 权威存储 | PostgreSQL 16 | 所有业务数据唯一可信来源 |
| 搜索 | Elasticsearch 8.x（官方镜像 + IK + pinyin 插件） | 纯搜索副本，索引可随时从 PG 全量重建 |

**核心原则：PG 是唯一权威数据源；ES 仅是可丢弃、可重建的搜索投影。ES 故障不丢任何数据。**

## 3. 数据模型（PostgreSQL）

### 3.1 `sys_user` 用户表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| username | VARCHAR(64) UNIQUE NOT NULL | 登录名 |
| password | VARCHAR(100) NOT NULL | BCrypt 哈希 |
| display_name | VARCHAR(64) | 显示名 |
| role | VARCHAR(16) NOT NULL | `ADMIN` / `EDITOR` / `VIEWER` |
| status | VARCHAR(16) NOT NULL | `ACTIVE` / `DISABLED` |
| created_at / updated_at | TIMESTAMPTZ | |

首期内置一个 `admin` 账号（启动时若不存在则创建，初始密码来自环境变量，强制首登不改密码逻辑不在本期）。无开放注册，账号由 ADMIN 在后台创建。

### 3.2 `segment` 句段条目表（核心）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| source_text | TEXT NOT NULL | 古文原文 |
| translated_text | TEXT NOT NULL | 英文译文 |
| work_title | VARCHAR(255) | 书名/篇名，如《论语》 |
| chapter | VARCHAR(255) | 章节号/篇目，如「学而」 |
| author | VARCHAR(255) | 原文作者，如「孔子及弟子」 |
| dynasty | VARCHAR(64) | 朝代，如「先秦」 |
| translator | VARCHAR(255) | 译者 |
| notes | TEXT | 备注 |
| status | VARCHAR(16) NOT NULL | `DRAFT` / `PUBLISHED`，默认 `PUBLISHED` |
| version | INT NOT NULL | 乐观锁版本号 |
| content_hash | VARCHAR(64) NOT NULL | SHA-256(source_text + '\u0000' + translated_text)，导入去重与重复检测用（'\u0000' 分隔符消除拼接歧义，Plan 2 修订） |
| created_by | BIGINT FK → sys_user | |
| created_at / updated_at | TIMESTAMPTZ | |

索引：`content_hash`（去重查重）、`dynasty`、`work_title`、`updated_at`（对账增量）、`(status)`。

朝代/书名不做独立字典表：首期为自由文本字段，筛选项由搜索聚合（facets）动态去重生成。

### 3.3 `tag` 与 `segment_tag`

- `tag`：id、name（UNIQUE NOT NULL）、description、created_at
- `segment_tag`：segment_id + tag_id 复合主键，FK 带级联删除

## 4. 搜索引擎设计（Elasticsearch）

### 4.1 索引 `segments`

一条 ES 文档对应 PG 一条 segment（含其标签名数组）。

| 字段 | 映射 | 用途 |
|---|---|---|
| source_text | `text`（ik_max_word 索引 / ik_smart 搜索）+ 子字段 `source_text.pinyin`（pinyin analyzer：全拼 + first_letter） | 中文分词检索；拼音/首字母命中 |
| translated_text | `text`（standard）+ `fuzziness=AUTO` | 英文全文 + 容错（如 `pleasa`→`pleasant`） |
| work_title | `keyword` + 子字段 `text`（ik）+ pinyin 子字段 | 精确过滤 + 分词检索 + 拼音命中 |
| author / dynasty / translator | `keyword` + ik `text` 子字段 | 精确过滤 + 模糊检索 |
| tags | `keyword[]` | 标签精确筛选与聚合 |
| status | `keyword` | 只搜已发布（VIEWER）；ADMIN/EDITOR 可搜草稿 |
| suggest | `completion`（输入：原文分词、书名、拼音） | 搜索框即时联想 |
| segment_id, created_at, updated_at | keyword / date | 回表定位与排序 |

### 4.2 查询行为

- `GET /api/v1/search?q=&tags=&dynasty=&work=&field=all|source|translation&page=&size=`
  - `q` 为空时 = 纯筛选浏览（标签/朝代/书名过滤 + 分页）
  - `field=all`：multi_match 跨 source_text、translated_text、work_title、pinyin 字段（拼音字段 boost 调低，避免拼音噪声压过原文命中）
  - 英文词元启用 `fuzziness=AUTO` 容错
  - 返回：总分页结构 + 命中字段**高亮**片段（`<em>` 标签）+ 标签聚合（facets）+ 朝代/书名聚合
- `GET /api/v1/suggest?q=`：completion suggester，返回候选短语与条目 id，输入 2 个字符即触发，P95 < 200ms
- 排序：默认相关度 `_score`；纯筛选浏览按 `updated_at desc`
- 分页上限：`size ≤ 50`，深翻页用 `from+size ≤ 1000`（首期不做 search_after）

### 4.3 降级策略

ES 连接失败/超时（阈值 2s）时，搜索接口自动降级为 PG `ILIKE` 查询（对 source_text/translated_text/work_title 匹配，无分词无拼音无高亮），响应带 `"degraded": true` 标记，前端展示"简化搜索模式"提示。降级不抛错，保证核心可用性。

## 5. PG → ES 数据同步

1. **实时同步**：写操作（创建/更新/删除/打标签）在 PG 事务提交后，通过 Spring `@TransactionalEventListener(AFTER_COMMIT)` 发布事件 → 异步（专用线程池）调用 ES bulk 更新对应文档。ES 写失败进入重试队列（内存队列 + 指数退避，最多 10 次）。
2. **对账兜底**：定时任务（默认每小时，可配置）按 `updated_at` 增量扫描 PG 近 24h 变更的条目，与 ES 比对 `_id + version`，补齐漏写。
3. **全量重建**：ADMIN 可触发 `POST /api/v1/admin/reindex`——从 PG 全量读取、bulk 写入新索引 `segments_v{n}`，完成后原子切换别名 `segments`。切换期间搜索短暂不中断。重建任务异步执行并可通过 `GET /api/v1/admin/reindex/status` 查询进度。

## 6. 认证与权限

- **认证**：JWT（HS256，密钥来自环境变量），有效期 24h，无刷新 token（过期重新登录）。`POST /api/v1/auth/login` 返回 token 与用户信息；`GET /api/v1/auth/me` 返回当前用户。
- **授权**：Spring Security 方法级注解（`@PreAuthorize`）。

| 角色 | 权限 |
|---|---|
| ADMIN | 一切：用户管理（增/禁用/改角色）、标签 CRUD、删除任意条目、重建索引 |
| EDITOR | 创建/编辑**任意**条目（不限于自己创建的，小团队互信场景）、批量导入、管理标签的"选用"（打标签）；不能删除条目（只能把条目改回草稿）、不能管理用户 |
| VIEWER | 搜索、浏览、查看详情（仅已发布条目） |

- 密码：BCrypt（强度 10）。
- 前端根据 `/auth/me` 返回的角色控制路由与按钮可见性；后端为最终裁决。

## 7. API 设计（REST，前缀 `/api/v1`）

统一响应体：`{"code": 0, "message": "ok", "data": {...}}`；非 0 为业务错误码（见 §9）。

| 方法与路径 | 说明 | 权限 |
|---|---|---|
| POST /auth/login | 登录，返回 JWT | 公开 |
| GET /auth/me | 当前用户信息 | 登录 |
| GET /segments | 分页列表（按书名/朝代/标签/状态筛选，updated_at 倒序） | 登录（VIEWER 仅 PUBLISHED） |
| GET /segments/{id} | 详情（含标签） | 登录（同上） |
| POST /segments | 创建 | EDITOR+ |
| PUT /segments/{id} | 更新（乐观锁 version） | EDITOR+ |
| DELETE /segments/{id} | 删除 | ADMIN |
| POST /segments/import | multipart 上传 JSON/CSV/Excel（≤ 50MB），返回预览 id | EDITOR+ |
| POST /segments/import/{previewId}/confirm | 确认导入，返回逐行成功/失败报告 | EDITOR+ |
| GET /search | 全局搜索（§4.2） | 登录 |
| GET /suggest | 搜索联想 | 登录 |
| GET /facets | 标签/朝代/书名聚合项（供筛选项渲染） | 登录 |
| GET/POST /tags、PUT/DELETE /tags/{id} | 标签管理（删除已关联条目的标签需 ADMIN） | 查=登录；写=EDITOR+；删=ADMIN |
| GET /users、POST /users、PUT /users/{id} | 用户管理（创建/改角色/禁用；不提供删除，禁用即封禁） | ADMIN |
| POST /admin/reindex、GET /admin/reindex/status | 全量重建搜索索引 / 查询进度 | ADMIN |

## 8. 批量导入设计

两阶段（上传→确认），避免误导入：

1. **上传与校验**：解析 JSON（对象数组）/ CSV / Excel（.xlsx，POI），逐行校验：source_text 与 translated_text 非空、字段长度、编码；`content_hash` 查重——库内重复默认**跳过**（可选参数 `duplicateStrategy=skip|overwrite|keep`）。生成预览报告（总行数、有效行、重复行、错误行及原因），返回 `previewId`（服务端内存 + 过期 30 分钟）。
2. **确认导入**：按 previewId 批量入库（JDBC batch，每批 1000），事务按批提交；完成后触发批量 ES bulk 同步；返回报告（成功 N、跳过 M、失败行号+原因列表）。

Excel 约定列头：`source_text, translated_text, work_title, chapter, author, dynasty, translator, notes, tags`（tags 为 `|` 分隔）。

## 9. 错误处理

- 全局 `@RestControllerAdvice`：参数校验（jakarta validation）返回 400 + 字段级错误；未认证 401；无权限 403；资源不存在 404；乐观锁冲突 409（提示刷新后重试）；服务器错误 500（不泄露堆栈）。
- 业务错误码分段：1xxx 认证、2xxx 条目、3xxx 导入、4xxx 搜索、5xxx 用户/标签管理。
- ES 降级见 §4.3；导入失败逐行报告（见 §8），不因个别行失败整体回滚（按批提交）。

## 10. 前端设计（Vue 3 SPA）

路由与页面：

| 路由 | 页面 | 说明 |
|---|---|---|
| /login | 登录页 | |
| / | **搜索首页（核心）** | 居中大搜索框（即时联想下拉）、标签/朝代/书名筛选栏（facets 动态渲染）、结果卡片（原文/译文对照 + `<em>` 高亮 + 标签 chips + 出处）、降级提示横幅、分页 |
| /segments/:id | 条目详情 | 完整字段 + 标签 + 编辑入口 |
| /segments/new、/segments/:id/edit | 条目编辑 | 表单：原文/译文/出处/标签多选（支持搜索新建标签） |
| /import | 批量导入向导 | 上传 → 预览校验报告 → 确认 → 结果报告 |
| /admin/users、/admin/tags | 管理后台 | 用户管理、标签管理（仅 ADMIN 可见入口） |
| /admin/reindex | 索引重建 | 进度展示（仅 ADMIN） |

- 状态：Pinia 存 token、当前用户、facets 缓存；axios 拦截器统一注入 JWT 与错误 toast。
- UI 语言中文；结果卡片采用"原文在上、译文在下"对照排版，古典风格配色（米白底、墨色字、朱砂点缀）。

## 11. 测试策略（TDD）

- **单元测试**：Service 层业务逻辑（导入解析校验、权限判断、内容 hash）用 Mockito 隔离。
- **集成测试**：Testcontainers 启动真实 PostgreSQL 与 Elasticsearch（IK/pinyin 插件镜像）——覆盖仓储、API 全链路、搜索相关性冒烟（固定语料断言：分词命中、拼音/首字母命中、英文容错命中、标签过滤、高亮存在）。
- **前端**：Vitest + Vue Test Utils 对核心组件（搜索框联想、筛选栏、导入向导状态机）做冒烟。
- 覆盖率目标：后端 Service/Search 层 ≥ 80%（JaCoCo 报告，不作硬性门禁）。

## 12. Docker 化部署

```
translation-database/
├── backend/                 # Spring Boot（多阶段 Dockerfile：maven 构建 → eclipse-temurin:21-jre）
├── frontend/                # Vue 3（Dockerfile：node 构建 → nginx 托管 + /api 反代）
├── docker/elasticsearch/    # Dockerfile：官方 8.x 镜像 + 安装 analysis-ik、analysis-pinyin
├── docker-compose.yml       # postgres / elasticsearch / backend / frontend
├── .env.example             # DB 密码、JWT 密钥、ES 内存等配置样例
└── docs/
```

- `docker-compose.yml` 要点：健康检查（pg_isready / ES _cluster/health / backend actuator /health）；`depends_on: condition: service_healthy`；PG 数据卷与 ES 数据卷持久化；ES 单节点、堆内存默认 2g（`.env` 可调）；backend 首次启动自动建表（Flyway 迁移脚本）并初始化 admin 账号。
- 启动顺序：PG/ES 健康后 backend 启动，backend 健康后 frontend 可用（frontend 实际随时可启动，Nginx 对 backend 不可达时返回 502 页）。

## 13. 项目与命名约定

- Maven `groupId`: `com.transdb`，artifact: `backend`，包根：`com.transdb`。
- API 路径、字段命名统一小驼峰；数据库列名 snake_case。
- Git 分支：单一 `main` 分支开发（个人项目，不引入分支流程）。
- 日志：SLF4J，搜索与导入接口记录耗时与条数（INFO），不记录敏感信息。

## 14. 关键决策记录

| 决策 | 结论 | 理由 |
|---|---|---|
| 搜索引擎 | ES 8（否决纯 PG / Meilisearch） | 拼音+容错+分词+聚合是 ES 插件生态甜蜜点；50 万条规模需要独立搜索层 |
| 数据模型 | 句段对照为核心 | 逐段积累、搜索粒度好（否决整篇文档型/两级结构） |
| 认证 | JWT + 三角色，无注册 | 满足权限需求的最小实现 |
| ES 同步 | 事务后事件 + 定时对账 + 手动重建 | 无新中间件（否决 outbox 表/CDC，首期复杂度过高） |
| 导入 | 两阶段上传确认 | 防误导入，50 万条目标必需 |
| ES 故障 | PG ILIKE 降级 | 核心搜索功能保底可用 |
