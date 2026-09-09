# 翻译学术数据库（Translation Academic Database）

存储与检索古典中文作品英译对照的学术数据库。句段级"古文原文 + 英文译文"对照，标签体系，
支持中文分词全文检索、拼音/首字母搜索、英文容错搜索（搜索能力见后续里程碑）。

## 设计文档

- 设计规格：`docs/superpowers/specs/2026-09-06-translation-database-design.md`
- 实施计划：`docs/superpowers/plans/`

## 技术栈

- 后端：Java 21、Spring Boot 3.3、PostgreSQL 16、Flyway、JWT
- 前端（规划中）：Vue 3 + Element Plus
- 搜索：Elasticsearch 8.13（IK 中文分词 + pinyin 拼音），PG→ES 实时同步 + 定时对账 + 全量重建

## 本地运行后端

前置：JDK 21、Maven 3.9+、本机 Docker（集成测试用 Testcontainers）。

```bash
# 启动一个本地 PostgreSQL（仅开发用）
docker run -d --name transdb-pg -p 5432:5432 \
  -e POSTGRES_USER=transdb -e POSTGRES_PASSWORD=transdb -e POSTGRES_DB=transdb \
  postgres:16-alpine

cd backend && mvn spring-boot:run
```

- 服务地址：http://localhost:8080
- 健康检查：http://localhost:8080/actuator/health
- 内置管理员：`admin` / `admin123`（生产环境务必通过环境变量 `TRANSDB_ADMIN_PASSWORD` 覆盖）
- 生产部署必须通过环境变量 `TRANSDB_JWT_SECRET` 设置强随机 JWT 密钥（≥32 字节），否则将使用仅适用于开发的内置默认密钥

## Docker 一键部署

```bash
cp .env.example .env   # 修改必填三项
docker compose up -d   # 首次构建 ES 插件镜像约需 5-10 分钟
```

- 访问 http://localhost（账号 admin / 你设置的 TRANSDB_ADMIN_PASSWORD）
- 宿主机内存建议 ≥ 8GB（ES 默认堆 2g）
- 纯 PG 开发（不起 ES）时搜索自动降级为简化模式，属正常现象

### 架构与容器

| 容器 | 说明 |
|---|---|
| frontend | Nginx：托管 Vue SPA + `/api` 反向代理 |
| backend | Spring Boot 3.3（Java 21，多阶段构建，非 root 运行） |
| postgres | PostgreSQL 16（数据卷 pgdata） |
| elasticsearch | ES 8.13 + IK/pinyin 插件（数据卷 esdata，堆内存 .env 可调） |

启动顺序由 healthcheck 保证：PG/ES 就绪 → backend 启动（Flyway 建表、初始化 admin、创建 ES 索引）→ frontend 可访问。

### 安全清单（生产）

- `TRANSDB_JWT_SECRET`：≥32 字节强随机（prod profile 下使用内置默认密钥将拒绝启动）
- `TRANSDB_ADMIN_PASSWORD`：首次启动创建 admin 用
- `TRANSDB_DB_PASSWORD`：数据库密码
- token 过期后前端自动跳转登录页（401 拦截）

## 运行测试

```bash
cd backend && mvn test
```

测试使用 Testcontainers 自动拉起 PostgreSQL 16 容器，无需本地数据库。

## API 一览（当前阶段）

| 方法与路径 | 说明 | 权限 |
|---|---|---|
| POST /api/v1/auth/login | 登录 | 公开 |
| GET /api/v1/auth/me | 当前用户 | 登录 |
| GET/POST/PUT/DELETE /api/v1/segments | 句段 CRUD | 见设计文档 §6 |
| GET/POST/PUT/DELETE /api/v1/tags | 标签管理 | 见设计文档 §6 |
| GET/POST/PUT /api/v1/users | 用户管理 | ADMIN |
| GET /api/v1/search | 全局搜索（中文分词/拼音/英文容错/高亮/聚合，ES 不可用时自动降级 PG） | 登录 |
| GET /api/v1/suggest | 搜索联想（书名/作者/标签，支持拼音与首字母） | 登录 |
| GET /api/v1/facets | 筛选项聚合（标签/朝代/书名） | 登录 |
| POST /api/v1/admin/reindex、GET .../status | 搜索索引全量重建与进度（ES） | ADMIN |
| POST /api/v1/segments/import、POST .../{previewId}/confirm | 两阶段批量导入·对照表（JSON/CSV/Excel，≤50MB/≤10 万行；重复策略 skip/overwrite/keep） | EDITOR+ |
| POST /api/v1/segments/import/document、POST .../{previewId}/confirm | 两阶段批量导入·整本书/文档（EPUB/PDF/Word(docx,doc)/TXT/Markdown/HTML，自动按章节拆段、仅原文译文留空默认 DRAFT；confirm 可携书目元数据覆盖；已有译文的原文自动保护跳过） | EDITOR+ |
| GET /api/v1/export/works、POST /api/v1/export/preview、POST /api/v1/export | 成书导出（书单统计/逐章配对预览/书稿文件下载；TXT/Markdown/Word × 仅译文/原文译文对照/仅原文；原文侧与译文侧分批导入的段按章节拉链配对，未配上留占位符） | EDITOR+ |

统一响应体：`{"code": 0, "message": "ok", "data": {...}}`。
