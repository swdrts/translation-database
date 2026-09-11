# 导入分段编辑器（Import Segment Editor）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文档/整本书导入向导新增第③步「调整分段」：全量分页预览所有分段（全文不截断），支持编辑文字、合并相邻段、按光标位置拆分、删除段、改章节归属与章节改名，每次编辑即时落到服务端内存会话并即时重算去重结论与统计，确认导入按编辑后的最终分段落库。

**Architecture:** 服务端可编辑会话——预览会话（`ImportPreviewStore`）本已持有全量 `ImportRowPlan` 列表，新增编辑服务在会话上原地增删改行，每次操作按预览同款规则重评受影响行的 planType（文件内去重→库内查重→重复策略），统计随之即时更新；前端向导从 3 步变 4 步，新组件 `SegmentEditorPanel.vue` 分页拉取并按章节导航/可疑段筛选。confirm 协议不变（仍只提交元数据覆盖）。

**Tech Stack:** 后端 Spring Boot（Java 21、record、JPA + `SegmentRepository` 批查、`BusinessException`）；前端 Vue 3 `<script setup>` + TypeScript + Element Plus + vitest（jsdom，全局注册 Element Plus）；后端测试为 `AbstractIntegrationTest` 集成测试 + 纯 JUnit 单测。

**设计文档:** `docs/superpowers/specs/2026-09-10-import-segment-editor-design.md`（已获批）。本计划中的接口签名以设计文档为准。

## Global Constraints

- 编辑端点仅对 `sourceType=DOCUMENT` 的会话开放；TABLE 会话调用返回 400（VALIDATION_FAILED）
- 权限全部 `hasAnyRole('EDITOR','ADMIN')` + 会话属主校验（operatorId 不符 → `IMPORT_PREVIEW_FORBIDDEN`）
- 会话过期/不存在复用 `IMPORT_PREVIEW_NOT_FOUND`（404/3003）；rowId 不存在 → 新增 `IMPORT_ROW_NOT_FOUND`（404/3006）
- 编辑不得产生空文本/纯空白段（400）；split 的 `atChar` 须满足 `0 < atChar < text.length()`
- merge 的 rowIds 须 ≥2 个且在会话当前顺序中连续（多个不连续段提交 400 提示首个断点位置）
- 拼接沿用 `TextSegmenter.joinSeparator` 语义：CJK 直接拼接、拉丁字母/数字间补一个空格
- 预览 VO 移除 `sampleRows` 字段（后端+前端同步删除）
- 章节字段空串与 null 等价（"未分章"）；`chapters` 端点的"未分章"用空串表示
- 每次写操作响应均携带 `stats: {totalRows, willImportRows, overwriteRows, skippedRows}`
- 行数上限 10 万（`ImportProperties.maxRows`）；rows 端点单页上限 500（默认 100）
- 后端测试命令：`cd backend && mvn test -Dtest=<ClassName>`（集成测试需 Docker 环境，若不可用注明跳过）；前端：`cd frontend && npm test`
- 提交规范沿用现有风格：中文 `feat:`/`fix:`/`test:` 前缀（git log 确认）；**提交前按用户全局规则：先向用户说明提交内容，获明确同意后才能 commit**

---

## File Structure

```
backend/src/main/java/com/transdb/
  common/ErrorCode.java                      [改] 新增 IMPORT_ROW_NOT_FOUND(3006)
  dto/ImportRowSampleVO.java                 [删] sampleRows 随字段移除
  dto/ImportPreviewVO.java                   [改] 移除 sampleRows 字段
  dto/ImportEditStatsVO.java                 [新] 写操作响应统计
  dto/ImportRowsPageVO.java                  [新] rows 分页响应（行 VO 内联 record）
  dto/ImportChapterStatVO.java               [新] chapters 响应
  dto/ImportRowEditRequest.java              [新] PATCH 行请求
  dto/ImportRowMergeRequest.java             [新] merge 请求
  dto/ImportRowSplitRequest.java             [新] split 请求
  dto/ImportChapterRenameRequest.java        [新] rename 请求
  importer/ImportRowPlan.java                [改] 增加 rowId/edited，withRow/withText 辅助
  importer/ImportPreviewStore.java           [改] 会话可变状态：nextRowId 计数器；create 注入 rowId
  importer/ImportPreviewService.java         [改] 移除 sampleRows；建会话时按行序分配 rowId
  importer/ImportPreviewEditService.java     [新] 编辑核心（七操作+重评+统计）
  controller/ImportController.java           [改] 新增 7 端点，抽 sessionOwned 工具
backend/src/test/java/com/transdb/
  importer/ImportPreviewEditServiceTest.java  [新] 纯单测：七操作+重评
  importer/ImportPreviewStoreTest.java        [改] nextRowId 分配测试
  controller/DocumentImportFlowTest.java      [改] sampleRows 断言移除；新增编辑端到端
  controller/ImportPreviewEditApiTest.java    [新] 端点属主/过期/校验/分页/筛选
  importer/ImportPreviewServiceTest.java      [改] 不再依赖 sampleRows 的断言（如有）
frontend/src/
  api/index.ts                               [改] 类型+7 个 api 方法
  components/DocumentImportPanel.vue         [改] 4 步向导；编辑器挂载
  components/SegmentEditorPanel.vue          [新] 分页列表+筛选+全部编辑操作
  components/SegmentRowActions.vue           [新] 行操作（编辑/拆分/删除/并入上段/改章节）
  tests/segment-editor.spec.ts               [新] 编辑器组件测试
  tests/import.spec.ts                        [改] 适配 4 步向导
docs/superpowers/plans/2026-09-11-import-segment-editor.md  [本计划]
```

---

### Task 1: ErrorCode 与 DTO 扩展（后端骨架）

**Files:**
- Modify: `backend/src/main/java/com/transdb/common/ErrorCode.java`
- Create: `backend/src/main/java/com/transdb/dto/ImportEditStatsVO.java`、`ImportRowsPageVO.java`、`ImportChapterStatVO.java`、`ImportRowEditRequest.java`、`ImportRowMergeRequest.java`、`ImportRowSplitRequest.java`、`ImportChapterRenameRequest.java`
- Test: 无独立测试（编译即验证）；后续任务消费

**Interfaces:**
- Produces:
  - `ErrorCode.IMPORT_ROW_NOT_FOUND`（HttpStatus.NOT_FOUND, 3006, "该分段在预览中不存在，请刷新后重试"）
  - `record ImportEditStatsVO(int totalRows, int willImportRows, int overwriteRows, int skippedRows)`
  - `record ImportRowsPageVO(ImportEditStatsVO stats, int page, int totalPages, List<RowVO> rows)`，内联 `record RowVO(long rowId, int seq, long prevRowId, String chapter, String text, String planType, boolean edited)`（prevRowId 无上段时为 -1；chapter null → ""；planType 取 "IMPORT"/"OVERWRITE"/"SKIP"）
  - `record ImportChapterStatVO(String title, long rowCount)`（未分章 title=""）
  - `record ImportRowEditRequest(String text, String chapter)`
  - `record ImportRowMergeRequest(List<Long> rowIds)`
  - `record ImportRowSplitRequest(int atChar)`
  - `record ImportChapterRenameRequest(String from, String to)`

- [ ] **Step 1: 在 ErrorCode 末尾新增一项（插在 EXPORT_WORK_NOT_FOUND 之前，保持导入段 30xx 连号）**

```java
    IMPORT_ROW_NOT_FOUND(HttpStatus.NOT_FOUND, 3006, "该分段在预览中不存在，请刷新后重试"),
    EXPORT_WORK_NOT_FOUND(HttpStatus.NOT_FOUND, 6001, "该书名不存在或没有可导出的内容");
```

- [ ] **Step 2: 创建 7 个 DTO record（各一文件，javadoc 一句话说明用途）**

ImportEditStatsVO.java：
```java
package com.transdb.dto;

/** 导入预览会话的当前统计：总会话行数与按 planType 的计数。 */
public record ImportEditStatsVO(int totalRows, int willImportRows, int overwriteRows, int skippedRows) {
}
```

ImportRowsPageVO.java：
```java
package com.transdb.dto;

import java.util.List;

/** 分段编辑器的分页数据。prevRowId 无上一段时为 -1；chapter 未分章为空串；planType 为 IMPORT/OVERWRITE/SKIP。 */
public record ImportRowsPageVO(ImportEditStatsVO stats, int page, int totalPages, List<RowVO> rows) {

    public record RowVO(long rowId, int seq, long prevRowId, String chapter, String text,
                        String planType, boolean edited) {
    }
}
```

ImportChapterStatVO.java：
```java
package com.transdb.dto;

/** 会话内章节及分段数；title 为空串代表未分章。 */
public record ImportChapterStatVO(String title, long rowCount) {
}
```

ImportRowEditRequest.java：
```java
package com.transdb.dto;

/** PATCH 编辑单段：text/chapter 至少传一个，均可选。 */
public record ImportRowEditRequest(String text, String chapter) {
}
```

ImportRowMergeRequest.java：
```java
package com.transdb.dto;

import java.util.List;

/** 合并连续分段：rowIds ≥2 且须为会话当前顺序中的连续段。 */
public record ImportRowMergeRequest(List<Long> rowIds) {
}
```

ImportRowSplitRequest.java：
```java
package com.transdb.dto;

/** 在 atChar 字符偏移处拆分为两段（0 < atChar < 文本长度）。 */
public record ImportRowSplitRequest(int atChar) {
}
```

ImportChapterRenameRequest.java：
```java
package com.transdb.dto;

/** 章节改名；from 为空串表示"未分章"；to 为空串表示把该章段归入"未分章"。 */
public record ImportChapterRenameRequest(String from, String to) {
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交（须先获用户同意）**

内容：ErrorCode + 7 个 DTO 新文件。建议信息：`feat: 导入分段编辑器——错误码与 DTO 骨架`

---

### Task 2: ImportRowPlan 加 rowId/edited，ImportPreviewStore 支持会话内分配

**Files:**
- Modify: `backend/src/main/java/com/transdb/importer/ImportRowPlan.java`
- Modify: `backend/src/main/java/com/transdb/ImportPreviewStore` → `backend/src/main/java/com/transdb/importer/ImportPreviewStore.java`
- Modify: `backend/src/main/java/com/transdb/importer/ImportPreviewService.java`
- Test: `backend/src/test/java/com/transdb/importer/ImportPreviewStoreTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ImportRowPlan(long rowId, PlanType type, ParsedRow row, Long existingSegmentId, String contentHash, boolean edited)`；辅助方法：
    - `ImportRowPlan withRow(ParsedRow newRow)` —— 保留 rowId/type/existingSegmentId/contentHash，edited 置 true（换行内容）
    - `ImportRowPlan withType(PlanType newType, Long newExistingId, String newHash)` —— 保留 rowId/row/edited
  - `ImportPreviewStore.ImportPreviewSession` 不变字段含义；`rows` 建会话时必须传入 ArrayList（可变）
  - `ImportPreviewStore` 新增：
    - `void requireOwned(String id, long operatorId, ImportSourceType requiredSourceType)` —— 取会话（惰性过期语义同 get）+ 校验属主与 sourceType，失败抛 BusinessException（NOT_FOUND/FORBIDDEN/VALIDATION_FAILED("该预览不支持分段编辑")）；**调用即续期**（见 Step 1 续期语义）
    - `long nextRowId(String id)` —— 分配并返回下一个 rowId（会话内单调递增，从 rows.size()+1 起）
  - `ImportPreviewService.buildPreview` 建会话时：plans 按行序赋 rowId（1..N），构造为 ArrayList，全部 `edited=false`

**设计要点（实现者必读）:**

- 会话 record 不可变的是字段引用；`rows` 是 `List<ImportRowPlan>` 引用——编辑服务对**同一个 ArrayList** 做 add/remove/set，`session.rows()` 永远读到最新内容。会话里新增 `AtomicLong rowCounter`（不可变字段持有可变对象），`create` 时初始化为 `plans.size()`。**不要**把 rowCounter 放进 record 构造参数——record 用紧凑方式在工厂方法里 new。
- 续期语义：`requireOwned` 与 rows 查询每次调用都把 `createdAt` 刷新为 now。record 不可变 → `ImportPreviewStore` 内部对会话做 `sessions.compute(id, (k,v) -> renewed)` 替换为新 record（同 id/同 rows 引用/新 createdAt）。sweepExpired 与惰性过期读 createdAt，无需改动。
- `ImportExecutor.applyOverrides`（ImportExecutor.java:117）里 `new ImportRowPlan(plan.type(), ...)` 的 4 参构造消失 → 改用 `plan.withRow(new ParsedRow(...))`（该路径不属编辑，edited 保持原值即可）。

- [ ] **Step 1: 写失败测试（ImportPreviewStoreTest 追加）**

```java
    @Test
    void nextRowIdIncrementsFromSessionRows() {
        ImportPreviewStore store = new ImportPreviewStore(new ImportProperties(100000, 60));
        ImportRowPlan p1 = new ImportRowPlan(1, ImportRowPlan.PlanType.IMPORT,
                new ParsedRow(1, java.util.Map.of("source_text", "甲")), null, "h1", false);
        ImportRowPlan p2 = new ImportRowPlan(2, ImportRowPlan.PlanType.IMPORT,
                new ParsedRow(2, java.util.Map.of("source_text", "乙")), null, "h2", false);
        String id = store.create(1L, DuplicateStrategy.SKIP,
                new java.util.ArrayList<>(List.of(p1, p2)), 2, List.<LineError>of(),
                ImportSourceType.DOCUMENT, SegmentStatus.DRAFT);
        assertThat(store.nextRowId(id)).isEqualTo(3);
        assertThat(store.nextRowId(id)).isEqualTo(4);
    }

    @Test
    void requireOwnedChecksOwnerSourceTypeAndRenewsTtl() {
        ImportPreviewStore store = new ImportPreviewStore(new ImportProperties(100000, 60));
        String id = store.create(1L, DuplicateStrategy.SKIP,
                new java.util.ArrayList<>(), 0, List.<LineError>of(),
                ImportSourceType.DOCUMENT, SegmentStatus.DRAFT);
        // 属主 + 类型正确 → 通过
        store.requireOwned(id, 1L, ImportSourceType.DOCUMENT);
        // 他人会话 → FORBIDDEN
        assertThatThrownBy(() -> store.requireOwned(id, 2L, ImportSourceType.DOCUMENT))
                .isInstanceOf(BusinessException.class);
        // TABLE 会话 → VALIDATION_FAILED
        String tableId = store.create(1L, DuplicateStrategy.SKIP,
                new java.util.ArrayList<>(), 0, List.<LineError>of());
        assertThatThrownBy(() -> store.requireOwned(tableId, 1L, ImportSourceType.DOCUMENT))
                .isInstanceOf(BusinessException.class);
        // 不存在 → IMPORT_PREVIEW_NOT_FOUND
        assertThatThrownBy(() -> store.requireOwned("nope", 1L, ImportSourceType.DOCUMENT))
                .isInstanceOf(BusinessException.class);
    }
```

（文件头补 import：`com.transdb.common.BusinessException`、`com.transdb.domain.SegmentStatus`、`static org.assertj.core.api.Assertions.assertThatThrownBy`）

- [ ] **Step 2: 运行确认编译失败**

Run: `cd backend && mvn -q test -Dtest=ImportPreviewStoreTest`
Expected: 编译错误（无 6 参构造/无 requireOwned/nextRowId）

- [ ] **Step 3: 实现 ImportRowPlan 与 ImportPreviewStore**

ImportRowPlan.java 整体替换为：
```java
package com.transdb.importer;

public record ImportRowPlan(long rowId, PlanType type, ParsedRow row,
                            Long existingSegmentId, String contentHash, boolean edited) {

    public enum PlanType { IMPORT, OVERWRITE, SKIP }

    /** 编辑行内容（文本/章节变化）：保留 rowId 与去重结论，标记已编辑。 */
    public ImportRowPlan withRow(ParsedRow newRow) {
        return new ImportRowPlan(rowId, type, newRow, existingSegmentId, contentHash, true);
    }

    /** 重评后更新去重结论：保留 rowId/row/edited。 */
    public ImportRowPlan withType(PlanType newType, Long newExistingId, String newHash) {
        return new ImportRowPlan(rowId, newType, row, newExistingId, newHash, edited);
    }
}
```

ImportPreviewStore：在 record `ImportPreviewSession` 中加字段 `java.util.concurrent.atomic.AtomicLong rowCounter`（加到 record 组件列表末尾，两参 create 均在工厂内 `new AtomicLong(rows.size())` 初始化）；新增方法：

```java
    /** 编辑/查询入口：校验存在、属主与 sourceType 后返回会话，并滑动续期。 */
    public ImportPreviewSession requireOwned(String id, long operatorId,
                                             ImportSourceType requiredSourceType) {
        ImportPreviewSession session = get(id);
        if (session == null) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_NOT_FOUND);
        }
        if (session.operatorId() != operatorId) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_FORBIDDEN);
        }
        if (session.sourceType() != requiredSourceType) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "该预览不支持分段编辑");
        }
        return renew(id, session);
    }

    /** 滑动续期：以新 createdAt 替换 map 槽位（rows 引用不变，编辑仍指向同一列表）。 */
    private ImportPreviewSession renew(String id, ImportPreviewSession s) {
        ImportPreviewSession renewed = new ImportPreviewSession(s.id(), s.operatorId(), s.strategy(),
                s.rows(), s.totalRows(), s.errors(), s.sourceType(), s.status(),
                Instant.now(), s.rowCounter());
        sessions.put(id, renewed);
        return renewed;
    }

    /** 分配下一个 rowId（会话内单调递增）。 */
    public long nextRowId(String id) {
        ImportPreviewSession session = sessions.get(id);
        if (session == null) {
            throw BusinessException.of(ErrorCode.IMPORT_PREVIEW_NOT_FOUND);
        }
        return session.rowCounter().incrementAndGet();
    }
```

（import 增加 `AtomicLong`、`com.transdb.common.BusinessException`、`com.transdb.common.ErrorCode`。create 工厂把 rows 包装为 `new ArrayList<>(rows)` 保证可变。）

- [ ] **Step 4: 修 ImportPreviewService 建会话处赋 rowId（L122-170 区域）**

plans 构造循环里每处 `new ImportRowPlan(PlanType.X, row, ...)` 改为 `new ImportRowPlan(nextRowId, PlanType.X, row, ..., false)`——循环前 `long nextRowId = 0;` 循环内 `nextRowId++` 先增后用。`previewStore.create(...)` 传入 `plans`（已是 ArrayList）。同时：
- 删除 `SAMPLE_ROWS`/`SAMPLE_TEXT_MAX` 常量、`sampleRows()` 与 `truncate()` 方法（L181-195）
- `ImportPreviewVO` 构造（L172-177）删去 `sampleRows` 参数（docMeta 为 null 时原传 `List.of()` 的分支一并删除）
- 顺手改 `ImportPreviewVO.java`：移除 `sampleRows` 字段与 `ImportRowSampleVO` import，删除 `ImportRowSampleVO.java` 文件
- 注意：**TABLE 导入路径（buildPreview(String, InputStream, ...)）与 DOCUMENT 路径共用同一私有 buildPreview**，rowId 赋值对两者无害（TABLE 也可有 rowId，编辑端点不开放即可）

- [ ] **Step 5: 修 ImportExecutor.applyOverrides 的构造调用（ImportExecutor.java:117-119）**

```java
            result.add(plan.withRow(new ParsedRow(plan.row().lineNumber(), fields)));
```
（替换原 3 行 `new ImportRowPlan(plan.type(), ...)`）

- [ ] **Step 6: 运行全部相关测试确认绿**

Run: `cd backend && mvn -q test -Dtest='ImportPreviewStoreTest,ImportPreviewServiceTest,ImportConfirmTest,DocumentImportFlowTest'`
Expected: 全 PASS（DocumentImportFlowTest L102 的 sampleRows 断言会失败 → 把它改为断言 `chapterCount` 与 `totalRows`，删除 sampleRows 相关断言行 L102-103）

- [ ] **Step 7: 提交（须先获用户同意）**

内容：ImportRowPlan/Store/Service/Executor 改动 + store 测试。建议信息：`feat: 导入会话行加稳定 rowId，预览移除 8 条抽样限制`

---

### Task 3: ImportPreviewEditService 编辑核心（纯逻辑，TDD）

**Files:**
- Create: `backend/src/main/java/com/transdb/importer/ImportPreviewEditService.java`
- Test: `backend/src/test/java/com/transdb/importer/ImportPreviewEditServiceTest.java`

**Interfaces:**
- Consumes: `ImportPreviewStore.requireOwned/nextRowId`、`ImportRowPlan.withRow/withType`、`ContentHash.sha256(source, translated)`、`SegmentRepository.findByContentHashIn/findBySourceTextIn/findByTranslatedTextIn`、`ImportPreviewService` 中既有 `hasOtherSide` 语义（本任务内自实现同款私有方法，避免循环依赖）
- Produces（全部 public，供 Task 4 controller 调用；`session` 均为 requireOwned 返回的会话）:
  - `ImportRowsPageVO rows(ImportPreviewSession session, ImportTextRole textRole, String chapter, boolean suspicious, int longAbove, int shortBelow, int page, int size)`
  - `List<ImportChapterStatVO> chapters(ImportPreviewSession session)`
  - `ImportRowPlan editRow(ImportPreviewSession session, ImportTextRole textRole, long rowId, ImportRowEditRequest req)` —— 返回更新后的 plan
  - `ImportRowPlan mergeRows(ImportPreviewSession session, ImportTextRole textRole, List<Long> rowIds)` —— 返回合并后的 plan
  - `List<ImportRowPlan> splitRow(ImportPreviewSession session, ImportTextRole textRole, long rowId, int atChar)` —— 返回 [上段, 下段]
  - `ImportEditStatsVO deleteRow(ImportPreviewSession session, long rowId)`
  - `ImportEditStatsVO renameChapter(ImportPreviewSession session, String from, String to)`
  - `ImportEditStatsVO statsOf(ImportPreviewSession session)` —— 对外统计

**实现规范（核心逻辑，必须照此写）:**

- 字段：`private final ImportPreviewStore previewStore; private final SegmentRepository segmentRepository;`（@Service @RequiredArgsConstructor）。`textRole` 由 controller 从会话行 fields 反推（见 Task 4），或**更简单**：会话建好后 textRole 不变，可在方法内由行 fields 推断（TRANSLATION 侧 source_text 为空串）——**采用**：`private ImportTextRole textRoleOf(session)`：首行 `row.get("source_text").isBlank() → TRANSLATION else SOURCE`。
- 行内容工具（textRole 相关）：
  - `private static String sideText(ParsedRow row, ImportTextRole role)`：`role == TRANSLATION ? row.get("translated_text") : row.get("source_text")`（null 安全转 ""）
  - `private static ParsedRow withSideText(ParsedRow row, ImportTextRole role, String text)`：复制 fields、set 对应键
  - `private static ParsedRow withChapter(ParsedRow row, String chapter)`：复制 fields、set("chapter", chapter)
  - 拼接：`private static String joinText(String a, String b)` —— 按 TextSegmenter.joinSeparator 语义：`a.isEmpty() || b.isEmpty() → a+b`；`a` 末字符与 `b` 首字符均为 ASCII 字母/数字（a 尾允许 ','）→ `a + " " + b`；否则 `a + b`（CJK 等直接拼接）。**复制逻辑而非调用私有方法**——TextSegmenter 的 joinSeparator 是 private static 且参数是 StringBuilder，复制这一小段（8 行）并在 javadoc 标注"与 TextSegmenter.joinSeparator 同语义"。
- `rows(...)`：过滤（chapter != null && !chapter.isBlank() 时匹配 `Objects.equals(displayChapter, chapter)`，displayChapter = null → ""；suspicious 时 `t.length() > longAbove || t.length() < shortBelow`，t 为 sideText）；过滤后分页（page 从 0 起，size 上限 500 下限 1，默认 100；越界页返回空 rows 但 totalPages 正确）；RowVO.seq = 过滤后列表中的 1-based 序号，prevRowId = 上一行 rowId（无则 -1）；`stats` 由 `statsOf(session)` 填。
- `statsOf`：遍历 rows 计数 totalRows=rows.size()、willImport=IMPORT 数、overwrite=OVERWRITE 数、skipped=SKIP 数。
- `chapters`：按行序 LinkedHashMap<String,Long> 计数（null → ""）；返回 VO 列表。
- `editRow`：
  1. `int idx = indexOfRowId(session, rowId)`，找不到抛 `IMPORT_ROW_NOT_FOUND`
  2. text 与 chapter 均为 null → 400 "text 与 chapter 至少修改一项"
  3. text != null 时 `text.strip()` 后 `isEmpty → 400 "分段内容不能为空"`；构造新 ParsedRow（withSideText + 若 chapter != null 也同时 withChapter）
  4. chapter != null 时：`chapter.strip()`；空白 → ""（归入未分章，允许）
  5. `plan.withRow(newRow)` 写回 `session.rows().set(idx, updated)`
  6. **text 变了** → `reevaluate(session, textRole, idx)`（见下）；只改章节则直接 `statsOf`
  7. 返回更新后的 plan
- `mergeRows`：
  1. rowIds null/size<2 → 400 "至少选择两段"
  2. 找到每个 rowId 的 idx（缺失 → IMPORT_ROW_NOT_FOUND）；**按会话顺序排序**后校验连续（`indices.get(i+1) == indices.get(i)+1`，断点 → 400 "分段不连续（从第 X 段开始断开）"）
  3. **去重 rowIds**（重复提交同一 id 只算一次）
  4. 从后往前删（保 idx 稳定）：`session.rows().removeAll(被合并 plans)`，第一段的 idx 处插入合并行
  5. 合并行构造：text = 依次 joinText 折叠；chapter 取**第一段**的；lineNumber 取第一段；fields 其他元数据（work_title/author 等）取第一段
  6. 合并行是**新行**：`rowId = previewStore.nextRowId(session.id())`、edited=true、type/hash 初值同第一段（随后重评覆盖）
  7. `reevaluate(session, textRole, 合并行 idx)`
  8. 返回合并后的 plan
- `splitRow`：
  1. 找 idx，校验 `atChar > 0 && atChar < text.length()`，否则 400 "拆分位置必须在段落文字中间"
  2. 上段 = text.substring(0, atChar).stripTrailing()、下段 = text.substring(atChar).stripLeading()（用 `String::strip` 不行——split 语义：`stripTrailing` 上段去尾空白、`stripLeading` 下段去头空白；任一结果空 → 400 "拆分后不能有空段"）
  3. 上段沿用原 rowId（保留"身份"）、下段 `rowId = previewStore.nextRowId(session.id())`；两段 chapter 同原段；edited=true
  4. `session.rows().set(idx, upper)`；`session.rows().add(idx+1, lower)`
  5. `reevaluate(session, textRole, idx)` 与 `reevaluate(session, textRole, idx+1)`（两段都要重评）
  6. 返回 [upper, lower]
- `deleteRow`：找 idx → `session.rows().remove(idx)` → 复活重评（见 reevaluate 死段处理）→ statsOf
- `renameChapter`：`from` null → ""；`to` null → 400 "新章节名不能为空"？——**否**：to 空白语义 = 归入未分章，**允许**。规则：`from` 匹配（null→""）、遍历所有行 `withChapter(plan.row(), to.strip())`（to 为空白 → ""），凡 chapter 变化的行 `withRow`（edited=true）。`from` 无任何匹配行 → 400 "章节不存在"。返回 statsOf。
- `reevaluate(session, textRole, idx)`（**编辑核心**，改正文/合并/拆分后必调）：
  1. 目标行 text = sideText；newHash = `ContentHash.sha256(row.get("source_text"), row.get("translated_text"))`（双侧原样取，拼 hash 用 fields 里的双键，**不要**用 sideText 拼）
  2. **文件内重评**：先看是否有**其他行**（排除自身 rowId）hash 相同：
     - 若存在且**那行 planType 不是 SKIP-by-file**（即 hash 相同的另一行是保留者）→ 目标行 planType=SKIP, existingSegmentId=null, hash=newHash → 完成重评（文件内重复标记）
     - 若存在且另一行本身也是文件内重复（双方同 hash 都是 SKIP 无 existingId）→ 保留者按顺序第一个（同 hash 的最早行）为保留者，目标行 SKIP
     - 若目标行自己是同 hash 的**最早行** → 目标行是保留者 → 继续走库内查重
  3. **库内查重**：`segmentRepository.findByContentHashIn(List.of(newHash))` 取到 existingId 基础上，再按 DOCUMENT 专属保护查导入侧整字段（TRANSLATION 侧查 `findByTranslatedTextIn(List.of(text))`、SOURCE 侧查 `findBySourceTextIn(List.of(text))`），任一命中且 `hasOtherSide` → SKIP 保护（existingId=命中的 id）；否则命中 hash → 按会话 strategy 分派 SKIP/OVERWRITE/IMPORT(KEEP)；未命中 → IMPORT
  4. `session.rows().set(idx, plan.withType(...))`
  5. **死段复活**：若第 2 步把目标行标为 SKIP-by-file（existingSegmentId == null），不影响他行；若目标行原来就是其他行的"保留者"（同 hash 有别的行因它被标 SKIP），删除/改写后那些行不再有保留者 → 对**同旧 hash 的所有行**重新跑一遍文件内重评+库内查重（受影响行限于同 hash 集合）
- `deleteRow` 的复活：删除前记 oldHash；删除后对同 oldHash 的剩余行逐个重评（若删除者是保留者，第一个同 hash 行恢复 IMPORT/OVERWRITE；若它本是文件内重复，无影响）
- **所有重评路径只触碰受影响行**（同 hash 集合 + 操作行），不做全表扫描重评

- [ ] **Step 1: 写失败测试（ImportPreviewEditServiceTest，纯 JUnit + Mockito，不起 Spring）**

测试类骨架（Mockito mock `SegmentRepository`；`ImportPreviewStore` 用真对象；构造 service 直接 new）：
```java
package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.domain.Role;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.*;
import com.transdb.repository.SegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 编辑服务纯逻辑测试：mock SegmentRepository，真实 ImportPreviewStore。 */
class ImportPreviewEditServiceTest {

    private ImportPreviewStore store;
    private SegmentRepository repo;
    private ImportPreviewEditService service;
    private com.transdb.domain.SysUser editor;

    @BeforeEach
    void setup() {
        store = new ImportPreviewStore(new ImportProperties(100000, 60));
        repo = mock(SegmentRepository.class);
        service = new ImportPreviewEditService(store, repo);
        editor = new com.transdb.domain.SysUser();
        // createUser 见下方 helper；editor 保存后有 id
    }

    // ---- helpers ----

    private static ParsedRow srcRow(int line, String text, String chapter) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("source_text", text);
        f.put("translated_text", "");
        f.put("chapter", chapter);
        f.put("work_title", "论语");
        return new ParsedRow(line, f);
    }

    private static ParsedRow dstRow(int line, String text, String chapter) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("source_text", "");
        f.put("translated_text", text);
        f.put("chapter", chapter);
        return new ParsedRow(line, f);
    }

    /** 建 DOCUMENT/SOURCE 会话并注册 editor 用户（LoginUser id 用固定值）。 */
    private ImportPreviewStore.ImportPreviewSession session(ParsedRow... rows) {
        List<ImportRowPlan> plans = new ArrayList<>();
        long rid = 1;
        for (ParsedRow r : rows) {
            plans.add(new ImportRowPlan(rid++, ImportRowPlan.PlanType.IMPORT, r, null,
                    com.transdb.common.ContentHash.sha256(r.get("source_text"), r.get("translated_text")), false));
        }
        String id = store.create(1L, DuplicateStrategy.SKIP, plans, plans.size(),
                List.of(), ImportSourceType.DOCUMENT, SegmentStatus.DRAFT);
        return store.requireOwned(id, 1L, ImportSourceType.DOCUMENT);
    }
```

用例清单（每条一个 @Test，断言见括号）：
```java
    @Test void rowsPaginatesFiltersAndMapsRowVO()          // 3 段、size=2、page=1 → 第 2 页 1 行；seq/prevRowId/planType 字段正确；chapter null → ""
    @Test void rowsSuspiciousFilterKeepsOnlyOddLengths()   // 5/500/8 字三段，longAbove=300 shortBelow=10 → 只返回 500 字段
    @Test void chaptersCountsByTitleWithUnassignedLast()   // 3 段两章+1 未分章 → [{学而第一,2},{为政第二,0} 不出现},  {"",1} 尾部]
    @Test void editRowChangesTextAndMarksEdited()          // editRow text → sideText 变化、edited=true、rowId 不变
    @Test void editRowRejectsBlankText()                   // text="  " → BusinessException VALIDATION_FAILED
    @Test void editRowRejectsWhenNothingChanged()          // text/chapter 均 null → 400
    @Test void editRowChangesChapterOnly()                // 只传 chapter → text 不变、edited=true、hash 不变
    @Test void mergeTwoRowsKeepsFirstChapterAndJoinsText() // 甲+乙 → "甲乙"（CJK 直拼）；rowId 为新分配；剩余 1 行
    @Test void mergeJoinsLatinWithSpace()                  // "Hello," + "world" → "Hello, world"
    @Test void mergeRejectsSingleRow()                     // 1 个 rowId → 400
    @Test void mergeRejectsDiscontinuousRows()             // row1+row3 → 400 含"不连续"
    @Test void mergeRowIdNotFound()                        // 不存在 id → IMPORT_ROW_NOT_FOUND
    @Test void splitAtCharProducesTwoRows()                // "学而时习之不亦说乎" atChar=5 → "学而时习之" + "不亦说乎"；下段新 rowId；两段 edited
    @Test void splitRejectsBoundaryAndBlankParts()()       // atChar=0 / length / 切出空白 → 400
    @Test void deleteRowRemovesAndReturnsStats()           // 3 段删 1 → stats.totalRows=2
    @Test void renameChapterUpdatesAllRowsInChapter()      // "学而第一"→"学而" → 2 行 chapter 变化 edited；chapters 反映新名
    @Test void renameChapterToExistingMergesChapters()    // 章 A→B → B 的 rowCount=合并数
    @Test void renameChapterMissingFromRejected()         // "不存在章" → 400
    @Test void renameChapterToBlankUnassigns()             // 章 A→"" → 该章行 chapter="" 且未分章计数增加
```

去重重评用例（重点，mock repo）：
```java
    @Test void editTextReevaluatesToImportWhenDuplicateGone()
        // 会话 2 行同文（初始建会话时第二行应为 SKIP——但 helper 建的都是 IMPORT；
        // 改为手动构造：row2 planType=SKIP, existingSegmentId=null（模拟文件内重复）。
        // repo.findByContentHashIn 返回空 → editRow(row2, "改过的句子") 后 row2 变 IMPORT

    @Test void editTextReevaluatesToSkipWhenMatchesDb()
        // repo.findByContentHashIn 返回命中段（hash 匹配）→ editRow 后 planType=SKIP、existingSegmentId=命中的 id

    @Test void editTextProtectedWhenDbHasOtherSide()
        // SOURCE 会话；repo.findBySourceTextIn 返回段（translatedText 非空）→ 编辑后 SKIP 保护 + existingId

    @Test void editTextStrategyOverwriteMakesOverwrite()
        // 会话 strategy=OVERWRITE；repo hash 命中 → editRow 后 planType=OVERWRITE

    @Test void deleteFirstOccurrenceRevivesFileDuplicates()
        // 2 行同文：row1 IMPORT（保留者）、row2 SKIP(existingId=null 文件内重复)。
        // deleteRow(row1) → row2 重评为 IMPORT

    @Test void mergeThenHashRerevaluationMarksSkipOnInFileDup()
        // 3 行：row1"甲乙"、row2"甲"、row3"乙"（均 IMPORT 初始）。
        // mergeRows([row2,row3]) → 合并行 hash==row1 的 hash → 合并行 SKIP(existingId=null)、row1 保持 IMPORT

    @Test void splitProducesNoFileDupWhenPartsDiffer()     // 拆分后两段 hash 互不相同且不与其他行重复 → 均 IMPORT
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ImportPreviewEditServiceTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 按"实现规范"实现 ImportPreviewEditService**

（按上述规范完整实现；类约 300 行。注意 reevaluate 内旧 hash 复活分支要覆盖 merge 的"合并者顶掉保留者"场景。）

- [ ] **Step 4: 运行测试至全绿**

Run: `cd backend && mvn -q test -Dtest=ImportPreviewEditServiceTest`
Expected: 全 PASS

- [ ] **Step 5: 提交（须先获用户同意）**

内容：编辑服务 + 单测。建议信息：`feat: 导入分段编辑核心——七操作与去重重评`

---

### Task 4: ImportController 新增 7 端点（集成测试）

**Files:**
- Modify: `backend/src/main/java/com/transdb/controller/ImportController.java`
- Test: `backend/src/test/java/com/transdb/controller/ImportPreviewEditApiTest.java`

**Interfaces:**
- Consumes: Task 3 全部 service 方法、Task 1 全部 DTO、`previewStore.requireOwned/nextRowId`、`LoginUser`
- Produces: HTTP API（前端 Task 6 消费）——路由见下表

| 方法与路由 | 请求 | 响应 |
|---|---|---|
| `GET /{previewId}/rows` | query: `chapter`(可空)、`suspicious`(bool, 默认 false)、`longAbove`(默认300)、`shortBelow`(默认10)、`page`(默认0)、`size`(默认100) | `ApiResponse<ImportRowsPageVO>` |
| `GET /{previewId}/chapters` | - | `ApiResponse<List<ImportChapterStatVO>>` |
| `PATCH /{previewId}/rows/{rowId}` | body `ImportRowEditRequest` | `ApiResponse<{row: RowVO, stats}>` → 定义 `record ImportRowOpResultVO(ImportRowsPageVO.RowVO row, ImportEditStatsVO stats)`（放 ImportRowsPageVO.java 内） |
| `POST /{previewId}/rows/merge` | body `ImportRowMergeRequest` | `ApiResponse<ImportRowOpResultVO>` |
| `POST /{previewId}/rows/{rowId}/split` | body `ImportRowSplitRequest` | `ApiResponse<{rows: List<RowVO>, stats}>` → `record ImportSplitResultVO(List<ImportRowsPageVO.RowVO> rows, ImportEditStatsVO stats)`（同文件） |
| `DELETE /{previewId}/rows/{rowId}` | - | `ApiResponse<ImportEditStatsVO>` |
| `POST /{previewId}/chapters/rename` | body `ImportChapterRenameRequest` | `ApiResponse<ImportEditStatsVO>` |

- [ ] **Step 1: 先写集成测试（失败）——ImportPreviewEditApiTest**

参照 `DocumentImportFlowTest` 的 helper（multipart 上传 TXT → 拿 previewId → 调编辑端点 → confirm → 断言落库）。用例：

```java
package com.transdb.controller;

/** 分段编辑端点：权限/属主/过期/分页/操作/端到端。 */
class ImportPreviewEditApiTest extends AbstractIntegrationTest {

    // 上传 helper 复用 DocumentImportFlowTest 的模式（可 copy multipart helper）

    @Test void editorCanListRowsAndChapters()               // 上传 2 段 TXT → GET rows 断言 rowId/seq/text 全文、stats.totalRows=2；GET chapters 含"未分章"
    @Test void rowsSuspiciousFilterWorksOverHttp()          // 上传 1 长 1 短段 → suspicious=true → 只回长段
    @Test void editSplitMergeDeleteChangeDbOutcome()        // 上传"甲乙丙丁。"1 段 → split(2)→"甲乙"+"丙丁。"→ merge → 还原 → editRow 改字 → confirm → 落库为改后文本、1 段
    @Test void renameChapterEndToEnd()                      // 上传 2 段（TXT 内容："第一句。\n\n第一章\n第二句。\n"）→ rename 未分章→"学而" → confirm → 落库 2 段 chapter 均为"学而"
    @Test void tableSessionCannotEdit()                    // uploadImport（json 对照表）→ PATCH rows/{rowId} → 400
    @Test void othersPreviewForbidden()                     // 用户 B 调 A 的 rows → 403
    @Test void expiredOrMissingPreview404()                // 编造 previewId → 404
    @Test void rowIdNotFound404()                         // 上传后 PATCH rows/99999 → 404 code=3006
}
```

- [ ] **Step 2: 运行确认失败（404 无路由）**

Run: `cd backend && mvn -q test -Dtest=ImportPreviewEditApiTest`
Expected: FAIL（端点不存在）

- [ ] **Step 3: 实现控制器端点**

ImportController 注入 `ImportPreviewEditService editService`；每端点开头：
```java
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
```
rows 端点（示例，其余同型）：
```java
    @GetMapping("/{previewId}/rows")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ImportRowsPageVO> rows(@PathVariable String previewId,
            @RequestParam(required = false) String chapter,
            @RequestParam(required = false, defaultValue = "false") boolean suspicious,
            @RequestParam(required = false, defaultValue = "300") int longAbove,
            @RequestParam(required = false, defaultValue = "10") int shortBelow,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "100") int size,
            @AuthenticationPrincipal LoginUser operator) {
        var session = previewStore.requireOwned(previewId, operator.id(), ImportSourceType.DOCUMENT);
        return ApiResponse.ok(editService.rows(session, chapter, suspicious, longAbove, shortBelow, page, size));
    }
```
注意：`ImportPreviewEditService.rows` 签名此处为 `(session, chapter, suspicious, longAbove, shortBelow, page, size)`——**textRole 由 service 内部 textRoleOf 推断**（Task 3 已定），controller 不传。chapters 同理。编辑端点把 service 返回的 plan 映射为 RowVO（映射逻辑放 service：`ImportRowsPageVO.RowVO toVO(ImportRowPlan plan, int seq, long prevRowId, ImportTextRole role)`——service 提供，controller 只取）。
split 响应：`new ImportSplitResultVO(rows.stream().map(...).toList(), stats)`；merge/edit 响应：`new ImportRowOpResultVO(toVO(...), stats)`。**toVO 放 service 内**（Task 3 补一个 public 方法，Task 4 controller 直接调）。

- [ ] **Step 4: 运行集成测试至绿**

Run: `cd backend && mvn -q test -Dtest=ImportPreviewEditApiTest`
Expected: 全 PASS

- [ ] **Step 5: 全量回归**

Run: `cd backend && mvn -q test`
Expected: 全 PASS（DocumentImportFlowTest 已在 Task 2 适配）

- [ ] **Step 6: 提交（须先获用户同意）**

内容：控制器 + 集成测试。建议信息：`feat: 导入分段编辑 7 端点——rows/chapters/merge/split/patch/delete/rename`

---

### Task 5: 前端 API 层（类型 + 7 个方法）

**Files:**
- Modify: `frontend/src/api/index.ts`
- Test: 前端组件测试（Task 7）将 mock 这些方法；本任务无独立测试文件

**Interfaces:**
- Consumes: Task 4 的 HTTP API
- Produces（Task 6/7 消费）:
  - 类型（替换现有 `ImportSampleRow`，删除之）：
```ts
export interface ImportEditStats {
  totalRows: number
  willImportRows: number
  overwriteRows: number
  skippedRows: number
}
export interface ImportSegmentRow {
  rowId: number
  seq: number
  prevRowId: number
  chapter: string
  text: string
  planType: 'IMPORT' | 'OVERWRITE' | 'SKIP'
  edited: boolean
}
export interface ImportRowsPage {
  stats: ImportEditStats
  page: number
  totalPages: number
  rows: ImportSegmentRow[]
}
export interface ImportChapterStat { title: string; rowCount: number }
export interface ImportRowOpResult { row: ImportSegmentRow; stats: ImportEditStats }
export interface ImportSplitResult { rows: ImportSegmentRow[]; stats: ImportEditStats }
```
  - `ImportPreview` 接口删除 `sampleRows?: ImportSampleRow[]` 字段
  - api 方法（追加到 api 对象）：
```ts
  listImportRows: (previewId: string, params: {
    chapter?: string; suspicious?: boolean; longAbove?: number; shortBelow?: number; page?: number; size?: number
  }) => http.get<never, ImportRowsPage>(`/segments/import/${previewId}/rows`, { params }),
  listImportChapters: (previewId: string) =>
    http.get<never, ImportChapterStat[]>(`/segments/import/${previewId}/chapters`),
  editImportRow: (previewId: string, rowId: number, body: { text?: string; chapter?: string }) =>
    http.patch<never, ImportRowOpResult>(`/segments/import/${previewId}/rows/${rowId}`, body),
  mergeImportRows: (previewId: string, rowIds: number[]) =>
    http.post<never, ImportRowOpResult>(`/segments/import/${previewId}/rows/merge`, { rowIds }),
  splitImportRow: (previewId: string, rowId: number, atChar: number) =>
    http.post<never, ImportSplitResult>(`/segments/import/${previewId}/rows/${rowId}/split`, { atChar }),
  deleteImportRow: (previewId: string, rowId: number) =>
    http.delete<never, ImportEditStats>(`/segments/import/${previewId}/rows/${rowId}`),
  renameImportChapter: (previewId: string, from: string, to: string) =>
    http.post<never, ImportEditStats>(`/segments/import/${previewId}/chapters/rename`, { from, to })
```

- [ ] **Step 1: 修改 index.ts**

按上述 Produces 全部落地：删 `ImportSampleRow` 与 `ImportPreview.sampleRows` 字段，加 7 个类型与 7 个方法。

- [ ] **Step 2: 类型检查**

Run: `cd frontend && npx vue-tsc -b --noEmit 2>&1 | head -30`（项目 build 脚本即 vue-tsc -b；此时 DocumentImportPanel 仍引用 sampleRows → 会报错，属预期）

Expected: 报错仅来自 `DocumentImportPanel.vue` / `import.spec.ts` 的 sampleRows 引用（Task 6/7 修复）；api 层自身无错

- [ ] **Step 3: 提交（须先获用户同意）**

内容：api/index.ts。建议信息：`feat: 前端 API 层——导入分段编辑 7 方法与类型`

---

### Task 6: SegmentEditorPanel 编辑器组件（含行操作子组件，TDD）

**Files:**
- Create: `frontend/src/components/SegmentEditorPanel.vue`、`frontend/src/components/SegmentRowActions.vue`
- Test: `frontend/src/tests/segment-editor.spec.ts`

**Interfaces:**
- Consumes: Task 5 全部 api 方法与类型；`ImportPreview.previewId`（prop）
- Produces:
  - Props: `{ previewId: string; sideNoun: string; onExpired: () => void }`（onExpired：410/404 预览丢失时向导重置回调——http 拦截器对业务错误 reject Error(message)，组件 catch 后判断 message 含"预览"即调 onExpired；由于拦截器已 toast，组件只回调）
  - Emits（含 stats-change）：`['expired', 'stats-change']`；每次写操作成功后：替换 stats → emit('stats-change', stats) → reload 当前页
  - 模板 `data-test` 锚点（测试与 Task 7 依赖，不得改名）：`editor-rows`（容器）、`editor-chapter-select`、`editor-suspicious-toggle`、`editor-long-above`、`editor-short-below`、`editor-merge-btn`、`editor-pager`、`editor-confirm-btn`、`editor-prev-page`、`editor-next-page`、`row-check`、`row-edit-btn`、`row-split-btn`、`row-delete-btn`、`row-merge-up-btn`、`row-chapter-btn`
  - 暴露（defineExpose，测试与父组件用）：`{ rows, stats, chapters, page, totalPages, checked, setChapter, toggleSuspicious, mergeChecked, loadRows, loadChapters }`——编辑器**不含**确认按钮：确认由父组件（DocumentImportPanel 第③步）持有
  - Emits：`['expired', 'stats-change']`（stats-change：ImportEditStats | null，loadRows/每次写操作成功后触发，供父组件启用/禁用确认按钮）
  - 内部状态：`rows: ImportSegmentRow[]`、`stats: ImportEditStats | null`、`chapters: ImportChapterStat[]`、`page: number`、`totalPages: number`、`checked: number[]`（rowId 集合）、`chapterFilter: string`（''=全部）、`suspicious: boolean`、`longAbove = 300`、`shortBelow = 10`、`busy: boolean`
  - 每次写操作（api 返回 stats）后也替换 stats 并 emit('stats-change', stats)
  - 失败处理：catch（拦截器已 toast）；若 error message 含"预览不存在"或"已过期"（404 3003）→ emit('expired')

**实现规范:**

- `loadRows()`：`api.listImportRows(previewId, { chapter: chapterFilter || undefined, suspicious: suspicious || undefined, longAbove, shortBelow, page, size: 100 })` → 赋值 rows/stats/totalPages；page 超界（如删除后当前页空）自动回退 `page = Math.max(0, page - 1)` 重查一次
- 分页器：`el-pagination` 或简易 prev/next 按钮 + `第 {page+1} / {totalPages} 页 · 每页 100 段`；翻页后 scroll 到列表顶部
- 章节下拉：`el-select`（filterable），options = `[{ title: '', label: '全部章节', rowCount: 全部 }]` + chapters；change → `page=0` → loadRows
- 可疑段开关：`el-checkbox` + 两个 `el-input-number`（min=1）；变更 → `page=0` → loadRows
- 行渲染：`v-for`（`:key="row.rowId"`），每行：复选框（v-model 收集 rowId 进 checked）、序号 `{{ row.seq }}`、章节小标签（有值时显示）、文本（`white-space: pre-wrap` 全文）、planType 标签（IMPORT→`新导入` 绿 / SKIP→`已存在跳过` 灰 / OVERWRITE→`将替换` 橙）、edited 标记（`已修改` 蓝）
- **合并所选**按钮：`checked.length >= 2` 才可用；点击前本地校验所选项在**当前 rows 列表**里连续（row.seq 相邻）——跨页勾选不支持（提示"只能在同一页内选择连续段落"）；调用 `api.mergeImportRows(previewId, checked)` → 成功后 `checked=[]` → reload
- **并入上段**（行按钮）：`api.mergeImportRows(previewId, [row.prevRowId, row.rowId])`；`row.prevRowId === -1` 时禁用
- **拆分**弹窗（SegmentRowActions 内或编辑器内联 el-dialog）：textarea readonly 展示全文 + `@click` 记录光标偏移（`selectionStart`）；显示实时预览上下两半；确定 → `api.splitImportRow(previewId, row.rowId, atChar)`
- **编辑文字**弹窗：textarea 可改；确定 → `api.editImportRow(previewId, row.rowId, { text })`；空文本前端先拦
- **删除**：`ElMessageBox.confirm('删掉这段？')` → `api.deleteImportRow(previewId, row.rowId)`
- **改章节**弹窗：`el-select` filterable allow-create，options 为 chapters（title 空显示"未分章"）→ `api.editImportRow(previewId, row.rowId, { chapter })`（选择"未分章"传 ''）
- **章节重命名**：章节下拉旁小按钮 → 弹窗（from = 当前 chapterFilter（须非 ''）或让用户从下拉选；to 输入）→ `api.renameImportChapter(previewId, from, to)` → 成功后 loadChapters + reload
- 挂载 `onMounted`：`loadChapters(); loadRows()`
- SegmentRowActions.vue：接收 `row` + emit 各操作事件（`edit`/`split`/`delete`/`merge-up`/`change-chapter`），渲染 4-5 个小按钮；弹窗放编辑器层（单处管理 api 调用）。**可并入 SegmentEditorPanel 单文件**——若拆出，只做纯展示/事件转发，无 api 调用

- [ ] **Step 1: 写失败测试（segment-editor.spec.ts）**

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SegmentEditorPanel from '../components/SegmentEditorPanel.vue'
import { api } from '../api'
import type { ImportRowsPage } from '../api'

vi.mock('../api', () => ({
  api: {
    listImportRows: vi.fn(),
    listImportChapters: vi.fn(),
    editImportRow: vi.fn(),
    mergeImportRows: vi.fn(),
    splitImportRow: vi.fn(),
    deleteImportRow: vi.fn(),
    renameImportChapter: vi.fn()
  }
}))
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') }
}))

const stats = { totalRows: 3, willImportRows: 2, overwriteRows: 0, skippedRows: 1 }
const page1: ImportRowsPage = {
  stats, page: 0, totalPages: 2,
  rows: [
    { rowId: 1, seq: 1, prevRowId: -1, chapter: '学而第一', text: '学而时习之，不亦说乎？', planType: 'IMPORT', edited: false },
    { rowId: 2, seq: 2, prevRowId: 1, chapter: '学而第一', text: '其为人也孝弟。', planType: 'IMPORT', edited: false },
    { rowId: 3, seq: 3, prevRowId: 2, chapter: '', text: '短', planType: 'SKIP', edited: true }
  ]
}

function mountEditor() {
  return mount(SegmentEditorPanel, {
    props: { previewId: 'p1', sideNoun: '段落' },
    global: { plugins: [createPinia()] }
  })
}

describe('SegmentEditorPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.listImportChapters).mockResolvedValue([
      { title: '学而第一', rowCount: 2 }, { title: '', rowCount: 1 }
    ])
    vi.mocked(api.listImportRows).mockResolvedValue(page1)
  })

  it('renders rows with full text, planType labels and edited mark', async () => {
    const w = mountEditor()
    await flushPromises()
    expect(w.text()).toContain('学而时习之，不亦说乎？')
    expect(w.text()).toContain('已存在跳过')   // SKIP 行
    expect(w.text()).toContain('已修改')       // edited 行
    expect(w.text()).toContain('学而第一')     // 章节标签
  })

  it('passes chapter filter and suspicious flags to listImportRows', async () => {
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any
    vm.setChapter('学而第一')
    await flushPromises()
    expect(api.listImportRows).toHaveBeenCalledWith('p1',
      expect.objectContaining({ chapter: '学而第一', page: 0 }))
    vm.toggleSuspicious()
    await flushPromises()
    expect(api.listImportRows).toHaveBeenCalledWith('p1',
      expect.objectContaining({ suspicious: true, longAbove: 300, shortBelow: 10 }))
  })

  it('merge checked rows calls api and reloads with stats', async () => {
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any
    vm.checked = [1, 2]
    vi.mocked(api.mergeImportRows).mockResolvedValue({
      row: { rowId: 4, seq: 1, prevRowId: -1, chapter: '学而第一', text: '合并段', planType: 'IMPORT', edited: true },
      stats: { totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0 }
    })
    await vm.mergeChecked()
    await flushPromises()
    expect(api.mergeImportRows).toHaveBeenCalledWith('p1', [1, 2])
    expect(vm.stats.totalRows).toBe(2)
    expect(api.listImportRows).toHaveBeenCalled()  // reload
  })

  it('rejects merge when checked rows are not adjacent', async () => {
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any
    vm.checked = [1, 3]
    await vm.mergeChecked()
    expect(api.mergeImportRows).not.toHaveBeenCalled()
  })

  it('row merge-up uses prevRowId pair', async () => {
    const w = mountEditor()
    await flushPromises()
    vi.mocked(api.mergeImportRows).mockResolvedValue({} as any)
    await w.find('[data-test="row-merge-up-btn"]').trigger('click')   // 第 2 行的并入上段
    await flushPromises()
    expect(api.mergeImportRows).toHaveBeenCalledWith('p1', [1, 2])
  })

  it('expired preview emits expired', async () => {
    vi.mocked(api.listImportRows).mockRejectedValue(Object.assign(new Error('导入预览不存在或已过期'), { response: { status: 404, data: { code: 3003 } } }))
    const w = mountEditor()
    await flushPromises()
    expect(w.emitted('expired')).toBeTruthy()
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npx vitest run src/tests/segment-editor.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 按"实现规范"实现两个组件**

- [ ] **Step 4: 运行测试至绿**

Run: `cd frontend && npx vitest run src/tests/segment-editor.spec.ts`
Expected: 全 PASS

- [ ] **Step 5: 提交（须先获用户同意）**

内容：编辑器组件 + 测试。建议信息：`feat: 分段编辑器组件——分页预览/章节筛选/可疑段/五类行操作`

---

### Task 7: DocumentImportPanel 四步向导改造（TDD）

**Files:**
- Modify: `frontend/src/components/DocumentImportPanel.vue`
- Test: `frontend/src/tests/import.spec.ts`（改造既有用例）

**Interfaces:**
- Consumes: Task 6 `SegmentEditorPanel`（props: previewId/sideNoun；emit: expired）、Task 5 api
- Produces: 4 步向导（内部 step 0/1/2/3 = ①上传 ②检查与补信息 ③调整分段 ④完成）；`data-test` 锚点新增：`doc-to-editor-btn`（②步"预览并调整分段"入口卡按钮）、`doc-confirm-btn`（③步确认按钮，沿用现有名）、`editor-expired-tip`（过期弹层文案）

**改造要点:**

- `el-steps` 四步：`① 选择文件` / `② 检查与补信息` / `③ 调整分段` / `④ 完成`
- 步骤②：**删除**「拆分出来的段落长这样」抽样表（L100-108 整块）；其位置换成入口卡：
```html
      <h3 class="section-title">📖 拆分出来的{{ sideNoun }}</h3>
      <div class="entry-card" data-test="doc-to-editor-btn" @click="step = 2">
        共 <b>{{ preview?.totalRows }}</b> 段<template v-if="preview?.chapterCount">，分 <b>{{ preview?.chapterCount }}</b> 章</template>。
        可逐段检查全文、合并拆分、修改章节——<b>点这里预览并调整分段</b>
      </div>
```
  ②步的「确认无误，开始导入」按钮**移除**（移到③步）；②步操作仅保留「不对，我要重新选」+ 新按钮「下一步：预览并调整分段」（`data-test="doc-to-editor-btn"`，等价入口卡点击）
- 步骤③（新 `v-else-if="step === 2"`）：`<SegmentEditorPanel :preview-id="preview!.previewId" :side-noun="sideNoun" @expired="onEditorExpired" />` + 底部操作区：
```html
      <div class="actions">
        <el-button size="large" @click="step = 1">← 上一步</el-button>
        <el-button type="primary" size="large" :loading="confirming"
                   :disabled="!editorStats || editorStats.totalRows === 0"
                   data-test="doc-confirm-btn" @click="confirm">
          确认无误，开始导入{{ editorStats ? `（${editorStats.totalRows} 段）` : '' }}
        </el-button>
      </div>
```
  `editorStats` 由编辑器 expose 的 stats 同步：`<SegmentEditorPanel ... @stats-change="editorStats = $event" />`——**改 Task 6 Produces**：编辑器增加 emit `stats-change`（ImportEditStats | null），loadRows/写操作后触发；父组件存 `editorStats = ref(null)`
- 步骤④：原 `v-else`（step===3 或更大）内容不变
- `onEditorExpired()`：`ElMessageBox.alert('预览已过期（超过 30 分钟未操作），请重新上传文件。', '预览过期', { type: 'warning' })` 后执行 `reset()`
- `confirm()` 不变（仍调 `api.confirmImport(previewId, meta)`）
- 结果步提示文案不变

- [ ] **Step 1: 改造既有测试（import.spec.ts 的 DocumentImportPanel describe）**

用例变更：
1. `preview shows samples...` → 重写为 `preview step shows entry card and stats, then editor on next`：
```ts
    it('step 2 entry card leads to segment editor, confirm from editor step', async () => {
      vi.mocked(api.uploadDocumentImport).mockResolvedValue({
        previewId: 'd1', strategy: 'SKIP', sourceType: 'DOCUMENT', textRole: 'SOURCE',
        totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0,
        errors: [], duplicates: [],
        documentTitle: '论语', documentAuthor: '孔门弟子', chapterCount: 2
      } as any)
      const wrapper = mount(DocumentImportPanel, { global: { plugins: [createPinia()] } })
      const panel = wrapper.vm as any
      await panel.runPreview(new File(['x'], 'lunyu.txt'))
      await flushPromises()

      // ② 步：无抽样表，有入口卡；识别书名已填
      expect(wrapper.text()).not.toContain('只显示前几条')
      expect(wrapper.text()).toContain('共 2 段')
      expect(panel.meta.workTitle).toBe('论语')

      await wrapper.find('[data-test="doc-to-editor-btn"]').trigger('click')
      expect(wrapper.findComponent(SegmentEditorPanel).exists()).toBe(true)

      // ③ 步确认（编辑器 stats 就绪才可用）
      ;(wrapper.findComponent(SegmentEditorPanel).vm as any).$emit('stats-change', { totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 1 })
      await flushPromises()
      panel.meta.workTitle = '论语（中华书局）'
      await panel.confirm()
      await flushPromises()
      expect(api.confirmImport).toHaveBeenCalledWith('d1', expect.objectContaining({ workTitle: '论语（中华书局）' }))
      expect(wrapper.text()).toContain('导入成功')
    })
```
   （spec 头部 vi.mock('../api') 增加 7 个编辑方法的 `vi.fn()`；mount 前需让 `listImportRows/listImportChapters` 有返回值——`vi.mocked(api.listImportChapters).mockResolvedValue([])` 等，避免编辑器 onMounted 报错）
2. 保留/顺改：`translation side upload...`（断言 `识别出译文段落` 不变）；`normalizes comma-separated tags`（同款加编辑器 mock 后跑 confirm，仍从③步调 confirm——该用例改为先进 editor 再 confirm，或直接 `panel.step = 2` + `panel.editorStats = {...}` 后 confirm）
3. import.spec 顶部 `vi.mock('../api', ...)` 的 api 对象补齐 7 个编辑方法 mock（组件 import 链会引用）

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npx vitest run src/tests/import.spec.ts`
Expected: FAIL（4 步未实现 / sampleRows 已删）

- [ ] **Step 3: 实现 DocumentImportPanel 改造**

- [ ] **Step 4: 运行 import.spec.ts + segment-editor.spec.ts 至绿**

Run: `cd frontend && npx vitest run src/tests/import.spec.ts src/tests/segment-editor.spec.ts`
Expected: 全 PASS

- [ ] **Step 5: 全量前端检查**

Run: `cd frontend && npm test && npx vue-tsc -b --noEmit`
Expected: 全 PASS、类型无错

- [ ] **Step 6: 提交（须先获用户同意）**

内容：向导 4 步改造 + 测试适配。建议信息：`feat: 导入向导四步化——第③步挂分段编辑器，确认移至编辑步`

---

### Task 8: 端到端冒烟 + 设计文档回写 + 收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-09-10-import-segment-editor-design.md`（"阶段"行更新为已实施）
- 无新代码

- [ ] **Step 1: 后端全量回归**

Run: `cd backend && mvn -q test`
Expected: 全 PASS

- [ ] **Step 2: 前端全量回归**

Run: `cd frontend && npm test && npx vue-tsc -b --noEmit && npm run build`
Expected: 全 PASS、构建成功

- [ ] **Step 3: 手动冒烟（需后端 + 前端 dev server；Docker PG/ES 可用则起真实栈，否则记录跳过）**

清单：上传 `lunyu.txt`（内容见下）→ ②步看统计与入口卡 → ③步翻页、切章节、勾可疑段 → 合并两段 → 拆分一段 → 改一段文字 → 删一段 → 把"未分章"改名"学而第一" → 确认 → 检查落库（chapter、文本、段数与编辑器 stats 一致）。

```
学而时习之，不亦说乎？有朋自远方来，不亦乐乎？

第一章

其为人也孝弟，而好犯上者，鲜矣。
```

- [ ] **Step 4: 更新设计文档状态行**

`- 状态：已获用户批准的设计稿` → `- 状态：已实施（实施计划 docs/superpowers/plans/2026-09-11-import-segment-editor.md）`

- [ ] **Step 5: 提交（须先获用户同意）**

内容：设计文档状态行。建议信息：`docs: 分段编辑器设计文档标记为已实施`

---

## Self-Review 记录

- 覆盖度：设计 §3（向导/编辑器 UI）→ Task 6/7；§4（7 端点）→ Task 4/5；§5（编辑+重评）→ Task 2/3；§6（异常）→ Task 3/4/6 各用例；§7（测试计划）→ 各任务测试步骤。sampleRows 移除 → Task 2/5/6/7。
- 类型一致性：`requireOwned`/`nextRowId`（Task 2 定义，3/4 消费）；`RowVO` 由 service 的 toVO 产出（Task 3 定义方法、Task 4 调用）；前端 `ImportRowsPage.stats` 字段与后端 `ImportRowsPageVO.stats` 对齐；emit `stats-change` 在 Task 6 定义、Task 7 消费。
- 无占位符：所有步骤含具体代码/断言；冒烟步骤含具体输入内容。

