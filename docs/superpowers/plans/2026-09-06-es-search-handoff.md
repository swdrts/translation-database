# Plan 2（搜索引擎）收尾记录与交接备忘

- 日期：2026-09-06
- 交付：`d53b7ed..9cbaf15`（13 个提交：10 个任务 + 2 次修复 + 修复波 + 文档），全部推送至 Gitee `main`
- 测试：后端 68/68 全绿（Testcontainers PG 16 + ES 8.13.4 插件镜像，真实 HTTP 集成测试）
- 终审：首次判定 Needs fixes（3 Important）→ 修复波 → 复审全部 ADDRESSED → Ready

## 一、交付能力

- **搜索**：`GET /api/v1/search`——中文分词（ik）+ 拼音/首字母（pinyin analyzer + phrase_prefix 前缀子句）+ 英文容错（fuzziness AUTO，有判别性测试）+ 原文译文双向 + 标签/朝代/书名过滤 + 高亮 + facets 聚合；VIEWER 强制仅见已发布；size≤50、from+size≤1000
- **联想**：`GET /api/v1/suggest`——completion contexts 按 书名/作者/标签 三类分组（各 5 条、skip_duplicates），支持拼音/首字母
- **筛选项**：`GET /api/v1/facets`（tags 50/dynasties 50/works 100）
- **同步**：条目与标签写操作 → AFTER_COMMIT 异步事件 → ES 文档；失败指数退避重试（≤10 次）；标签改名/删除传播至受影响句段；每小时对账（每批 500 短事务）；`POST /api/v1/admin/reindex` 全量重建（bulk 逐项错误校验、原子别名切换、孤儿索引清理、FAILED 状态），支持别名缺失自愈
- **降级**：熔断（连续 3 次失败 → 30s 开路）→ PG ILIKE 兜底（`degraded:true`、PG distinct facets）
- **规格修订**：§3.2 ContentHash 增加 `'\u0000'` 分隔符；§4.2 suggest 不返回条目 id（聚合候选点击触发搜索而非跳转）

## 二、主控裁定记录（未逐项请示用户，已在总结中列明）

1. **拼音搜索实现**：部分拼音（`zhongyong`/`zy`）与索引词元（`zhongyongceshi`）是前缀关系，multi_match+fuzziness 永远命不中 → 增加平行 `match_phrase_prefix` 子句（should + minimum_should_match:1，纯 OR 超集，无映射变更）。副作用：纯中文查询也会被拼音转写产生轻微音近召回（相关度调优时观察）。
2. **suggest 不返回条目 id**（修订规格而非加半吊子 id 字段）：聚合候选（书名被 N 条共享）与单个条目无对应关系；联想点击应触发搜索。
3. **reindex 容错语义**：bulk HTTP 200 + `errors:true` 必须抛异常走 FAILED（否则静默丢文档）；别名缺失视为零旧索引、照常重建（启动期 WARN 承诺的自愈路径）。

## 三、移交 Plan 3（导入）的准入/优化项

- ContentHash 金标值（golden vector）测试随导入大规模使用时补上
- 对账的逐文档 ES GET（N+1）→ 与导入批量同步一起改 `_mget`/ids 批量
- 标签变更逐句段发 N 个事件 → 与导入批量路径一起合并为 bulk 批处理

## 四、移交 Plan 4（前端 + 部署）的准入/决策项

- JWT fail-fast：非开发环境使用内置 dev secret 时拒绝启动（Plan 1 遗留）
- PG 降级 facets 未过滤 VIEWER/草稿（count=0 的名称可能出现在降级模式筛选项）——随前端 facets 渲染一起修
- suggest 联想可能含草稿衍生的书名/作者（无状态过滤）——如需收紧，在 suggest 查询加 status filter（MVP 接受为已知限制）
- README 补一句"本地无 ES 时 /actuator/health 显示 DOWN 属正常（应用自动降级）"；compose 文档含 vm.max_map_count 说明（如需）
- 运维：换别名后旧索引删除部分失败会留孤儿全量索引（别名正确性不受影响）——清理脚本/运维手册；SyncRetryQueue 排空与对账共享单线程调度器的竞争（MVP 无碍）；可观测性钩子（修复计数/重试队列深度）

## 五、已冻结的契约（Plan 3/4 依赖）

- 搜索响应：`SearchResponseVO{content[SearchItemVO], total, page, size, degraded, facets{tags,dynasties,works[FacetItem]}}`；`SearchItemVO.highlight` 为 `<em>` 片段 map
- `SuggestVO{works, authors, tags}`（字符串列表）；`GET /api/v1/suggest?q=`
- `POST /api/v1/admin/reindex` / `GET /api/v1/admin/reindex/status` → `ReindexStatusVO{state: IDLE/RUNNING/DONE/FAILED, indexed, total, startedAt, finishedAt, error}`
- 错误码新增：`REINDEX_ALREADY_RUNNING(409, 4001)`
- ES 镜像 `transdb/elasticsearch-ik-pinyin:8.13.4`（docker/elasticsearch/Dockerfile，部署与测试同源）
