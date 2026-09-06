# Plan 1（后端核心）收尾记录与交接备忘

- 日期：2026-09-06
- 交付：`7a254da..262d2c9`（13 个功能/修复提交 + 3 个文档提交），全部推送至 Gitee `main`
- 测试：后端 40/40 全绿（Testcontainers PostgreSQL 16，真实 HTTP 集成测试）
- 终审：Ready——无 Critical；4 个 Important 均为面向 Plan 2-4 的设计决策，非交付缺陷

## 一、待用户确认的两项裁定（AskUserQuestion 未获回复，由主控按最佳判断裁定）

1. **条目更新语义**：`PUT /api/v1/segments/{id}` 不传 `status`（null）→ **保留当前状态**（防止改句子时把 DRAFT 静默发布）；仅创建时 null 默认 PUBLISHED。不传 `tagIds` → 保留现有标签。
   - 依据：审查者指出原计划语义会"改个句子顺便发布草稿"；部分更新语义与 users PUT 一致。
   - 如不认可：改回"null 默认 PUBLISHED"只需调整 `SegmentService.applyUpsert` 一处 + 测试。
2. **管理员自我保护**：禁止 ADMIN 修改**自己的** role/status（任何提交即 400/5005）；displayName 自改允许。
   - 依据：无状态 JWT 下，被禁用的 ADMIN 若可自我重新启用，禁用形同虚设（原实现缺陷）。

## 二、已知限制（设计文档 §6 自身选择，需用户知情确认）

- **被禁用用户在 token 过期前（≤24h）仍可访问 API**：JwtAuthFilter 只信 token claims，不做每请求查库。
  - 可选加固：filter 内每请求查库校验 status（可用短期缓存），代价是每请求一次 DB 查询。
  - 终审建议：先作为已知限制记录；如需即时生效的封禁，在 Plan 2 一并实现。

## 三、后续计划的准入决策（终审 Important 发现，开工前必须解决）

1. **Plan 2（搜索）**：标签改名/删除是 ES 同步盲区——`TagService.update/delete` 不发布 `SegmentChangedEvent` 也不更新 `segment.updated_at`，§5.2 的对账任务永远修复不了受影响句段文档。Plan 2 方案必须含：标签变更时批量发布受影响 segment 事件，或标签变更时 bump 相关 segment 的 updated_at。
2. **Plan 3（导入）开工前**：`ContentHash.sha256(source + translated)` 存在拼接歧义（`("ab","c")` 与 `("a","bc")` 同哈希），会静默跳过合法条目。建议改为带分隔符/长度前缀（一行代码 + 全量重算），**现在改成本最低**（50 万行导入后重哈希成本高）。
3. **Plan 4（部署）**：启动时 fail-fast 校验——非开发环境使用内置 dev JWT secret 时拒绝启动；README 已补充说明（262d2c9）。

## 四、终审 minor 分诊（b/c 类，均已记录）

- 移交 Plan 2：`signWith` 钉死 HS256；无效 tagIds 静默丢弃 → 校验报错；DTO `@Size` 上限；未测分支补测（1001 未知用户、标签 404/5003、改名撞名、VIEWER 写 403、size 钳制）。
- 接受现状：TOCTOU 并发重复（DB 约束兜底）、N+1 列表查询（搜索路径 Plan 2 迁 ES）、样式类问题等。

## 五、Plan 2-4 依赖的代码契约（已冻结）

- `SegmentChangedEvent(Long segmentId, ChangeType{CREATED,UPDATED,DELETED})`，事务内发布，消费用 `@TransactionalEventListener(AFTER_COMMIT)`
- `ContentHash.sha256(String source, String translated)` → 64 位小写 hex（Plan 3 导入复用——注意第二节第 2 点的待改事项）
- 统一响应 `ApiResponse{code,message,data}`；错误码全表见 `common/ErrorCode`
- 测试基建：`AbstractIntegrationTest`（单例 PG 容器 + `createUser(Role)`/`bearer(SysUser)`）；tag.name/content_hash 唯一名测试纪律
