# Plan 3（批量导入）收尾记录与交接备忘

- 日期：2026-09-07
- 交付：`8ca5e83..80b840b`（9 个提交：6 任务 + 2 修复轮 + 终审修复波 + 文档），全部推送至 Gitee `main`
- 测试：后端 102/102 全绿（Testcontainers PG 16 + ES 8.13.4，真实 HTTP 集成测试）
- 终审：首次判定 Needs fixes（4 Important）→ 修复波 → 复审 4/4 ADDRESSED → Ready

## 一、交付能力

- **两阶段导入**：`POST /api/v1/segments/import`（上传→校验→重复检测→预览报告）→ `POST .../{previewId}/confirm`（执行→逐行结果）。属主绑定、30 分钟 TTL + 定时清扫、确认即消费（防重放）
- **格式**：JSON（snake/camel 键别名、tags 数组）、CSV（RFC4180 引号转义、规范化表头碰撞拒绝 3001）、Excel（POI、数值显示值、同款碰撞拒绝）
- **重复策略**：skip（默认，跳过）、overwrite（库内 JPA 更新 version+1；文件内重复**后写胜**）、keep（照常导入）
- **执行**：IMPORT 行 JDBC 每批 1000（每批短事务）+ 标签 resolve-or-create + segment_tag 批插；OVERWRITE 每批 100 JPA 更新；失败行逐行报告不阻断他行
- **ES 同步**：JDBC 路径不发领域事件，导入完成后 `bulkUpsert` 按批 500 显式同步（`BulkResponseGuard` 从 ReindexService 提取共享）；失败仅告警由对账兜底
- **错误语义**：3001 格式不支持/解析失败、3002 文件超限（含 multipart 超限映射）、3003 预览不存在/过期/已消费、3004 非本人预览、3005 空数据

## 二、主控裁定记录

1. **JSON 非 tags 嵌套值静默置空**（plan-mandated，接受为 MVP 已知限制）：必填列由行校验拦截，可选列静默置空
2. **OVERWRITE 后写胜**（终审纠正实现为计划语义）：文件内同 hash 碰撞时以较后一行的元数据为准
3. **预览会话内存态 + 定时清扫**：单实例假设（多实例部署需换 Redis/DB，Plan 4 范畴外）

## 三、规模就绪度（50 万条目标，终评估）

- 100k 行/文件、行式插入约 1-5k 行/秒（10 万条约 1-2 分钟，满足"分钟级"）；50 万 = 5 个文件分批
- 真瓶颈：① `executeAndReturnKey` 逐行取主键（真批需 JDBC returning 改造，冲 50 万时做）；② 内存预览会话（每会话数十 MB，已有 TTL 清扫）；③ xlsx DOM 模式 50MB 展开数百 MB XML（EDITOR 半可信，接受为操作风险，可选 xlsx 单独降低文件上限）

## 四、移交 Plan 4（前端 + 部署）的项

- 前端契约：`ImportPreviewVO{previewId, strategy, totalRows, willImportRows, overwriteRows, skippedRows, errors[LineError], duplicates[LineError]}`、`ImportResultVO{imported, overwritten, skipped, failed[LineError]}`；`LineError{line, reason}`；预览 errors 与 duplicates 分开渲染；`skippedRows` 预览口径含文件内重复（结果口径只算 SKIP 计划）——前端两处计数含义不同需注意
- 死代码清理：`ImportExecutor.previewStore` 字段未用、`ImportController` 的 `file == null` 分支不可达
- 诊断债：批次失败日志无堆栈（`log.warn(msg, e.getMessage())` → 应传 `e`）
- JSON 同对象内 snake/camel 键规范化冲突后写胜（静默）；Excel 空表头单元格碰撞整文件 3001（真实文件可能有杂散空列，宜过滤空名）
- CSV 超长行静默丢多余列（短行由必填校验兜底）；`.xls`/0 字节/引号内换行无持久测试
- 运维：导入日志、重试队列/清扫可观测性

## 五、已冻结契约（Plan 4 依赖）

- 导入 API 与 VO（见上）；`POST /api/v1/segments/import` 参数：multipart `file` + `duplicateStrategy`（SKIP/OVERWRITE/KEEP，大小写不敏感，默认 SKIP）
- 前序契约不变：搜索/联想/facets/reindex/认证（Plan 2 收尾备忘）
