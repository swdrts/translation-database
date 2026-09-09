# 成书导出（Book Export）实施计划 — Plan 1/1

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按书名把库内句段合并装配成书稿并导出下载——支持 仅译文 / 原文译文对照 / 仅原文 三种模式 × TXT / Markdown / Word(docx) 三种格式，导出前提供书单统计与逐章配对预览（原文侧/译文侧分批导入的段按章节拉链配对）。

**Architecture:** 新代码集中在 `com.transdb.exporter` 包。`BookAssembler`（纯逻辑）把整本书的段按"章节（最小 id 定序）→ 段 id 序"分类装配：完整段直通、纯原文段与纯译文段同章拉链配对、残留段带占位符；输出 `BookDocument`（渲染模型）与 `BookStats`（预览统计）。三个 `BookRenderer` 实现（TXT/MD/DOCX，POI XWPF，无新依赖）渲染成书文件。`ExportService` + `ExportController` 暴露 3 个端点（书单/预览/下载，均 EDITOR+），下载端点是项目首个文件流响应。前端新增「成书导出」页（书单卡片 → 预览弹窗 → blob 下载），`http.ts` 加 blob 旁路。

**Tech Stack:** 后端 Spring Boot 3.3.5 / Java 21 / Maven（**无 mvnw，用系统 `mvn`**）；Apache POI poi-ooxml 5.3.0（XWPF 生成 docx，已有依赖，不新增）；前端 Vue 3 + TS + Element Plus（全量引入）+ vitest。

## Global Constraints（每个任务隐含遵守）

- **【用户硬性规则·最高优先级】严禁任何 git 写操作**：不执行 `git add/commit/push/merge/rebase/checkout/stash/clean`，也不派 subagent 执行。每个任务完成后改动**保留在工作区**，由主控统一向用户确认是否提交。计划中原有的 "Commit" 步骤一律替换为"检查点"步骤。
- 设计文档 `docs/superpowers/specs/2026-09-09-book-export-design.md` 为绑定章节。
- 后端不新增任何 Maven 依赖（DOCX 用已有 `poi-ooxml` 5.3.0 的 XWPF）。
- 权限：导出三端点均 `@PreAuthorize("hasAnyRole('EDITOR','ADMIN')")`。
- 错误码：导出用新 6xxx 段，仅 `EXPORT_WORK_NOT_FOUND(HttpStatus.NOT_FOUND, 6001, "该书名不存在或没有可导出的内容")`。
- 占位符文案：未译 `〔未译〕`、缺原文 `〔原文缺失〕`；文件名 `{书名}-{对照|译文|原文}.{txt|md|docx}`，书名清洗 `[\\/:*?"<>|]` → `_`。
- 编码：TXT = UTF-8 **带 BOM** + CRLF；MD = UTF-8 无 BOM + LF；DOCX 二进制。
- `segment.translated_text` 列 NOT NULL——"无译文"以**空串**存储；一切"是否有译文"判断用 `isBlank()`（JPQL 统计用 `is not null and <> ''`）。
- 既有测试不回退：`mvn -f backend/pom.xml test` 与 `cd frontend && npm run test` 全绿。集成测试基于 Testcontainers 需本机 Docker；若 Docker 不可用，必须至少跑通纯 JUnit 测试类并如实报告跳过项。
- 测试数据用唯一值（书名带 nanoTime 后缀），不得依赖执行顺序。

---

### Task 1: 书稿模型 + BookAssembler 配对装配（纯 JUnit TDD）

**Files:**
- Create: `backend/src/main/java/com/transdb/exporter/BookDocument.java`
- Create: `backend/src/main/java/com/transdb/exporter/BookStats.java`
- Create: `backend/src/main/java/com/transdb/exporter/AssembledBook.java`
- Create: `backend/src/main/java/com/transdb/exporter/BookAssembler.java`
- Test: `backend/src/test/java/com/transdb/exporter/BookAssemblerTest.java`

**Interfaces:**
- Consumes: `com.transdb.domain.Segment`（Lombok getter：`getSourceText()/getTranslatedText()/getWorkTitle()/getChapter()/getAuthor()/getTranslator()`，均可空字符串）
- Produces:
  - `record BookUnit(String source, String translated)`（嵌套于 `BookDocument`；`source`/`translated` 为 null 表示缺失）+ 常量 `BookUnit.UNTRANSLATED_PLACEHOLDER = "〔未译〕"`、`BookUnit.MISSING_SOURCE_PLACEHOLDER = "〔原文缺失〕"` + 方法 `String sourceSafe()` / `String translatedSafe()`（null → 对应占位符）
  - `record BookDocument(String title, String author, String translator, List<BookChapter> chapters)`（嵌套 `record BookChapter(String title, List<BookUnit> units)`；`title` 为 null 表示无章节书）
  - `record BookStats(int segments, int units, int pairedUnits, List<ChapterStat> chapters)`（嵌套 `record ChapterStat(String title, int full, int src, int dst, int paired, List<String> warnings)`；`paired` = min(src, dst)）
  - `record AssembledBook(BookDocument document, BookStats stats)`
  - `BookAssembler.assemble(List<Segment> segments)` → `AssembledBook`；**要求入参已按 id 升序**（由仓库方法保证）；类为无状态 `@Component`（构造无依赖）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/exporter/BookAssemblerTest.java`：

```java
package com.transdb.exporter;

import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookAssemblerTest {

    private final BookAssembler assembler = new BookAssembler();

    /** 依 id 语义构造段：翻译缺失传空串（列 NOT NULL，与库内存储一致）。 */
    private Segment seg(String src, String dst, String chapter) {
        Segment s = new Segment();
        s.setSourceText(src);
        s.setTranslatedText(dst);
        s.setWorkTitle("论语");
        s.setChapter(chapter);
        s.setAuthor(null);
        s.setStatus(SegmentStatus.DRAFT);
        return s;
    }

    @Test
    void fullSegmentsPassThroughInIdOrder() {
        AssembledBook book = assembler.assemble(List.of(
                seg("学而时习之", "To learn...", "学而第一"),
                seg("有朋自远方来", "Is it not delightful...", "学而第一")));
        assertThat(book.document().getTitle()).isEqualTo("论语");
        assertThat(book.document().getChapters()).hasSize(1);
        assertThat(book.document().getChapters().get(0).units())
                .extracting(BookDocument.BookUnit::source, BookDocument.BookUnit::translated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("学而时习之", "To learn..."),
                        org.assertj.core.groups.Tuple.tuple("有朋自远方来", "Is it not delightful..."));
        assertThat(book.stats().segments()).isEqualTo(2);
        assertThat(book.stats().units()).isEqualTo(2);
        assertThat(book.stats().pairedUnits()).isEqualTo(2);
        assertThat(book.stats().getChapters().get(0).warnings()).isEmpty();
    }

    @Test
    void splitSideSegmentsZipByPositionWithinChapter() {
        // 原文侧先导入（id 小）：3 段纯原文；译文侧后导入：3 段纯译文 → 同章拉链配对
        AssembledBook book = assembler.assemble(List.of(
                seg("甲", "", "学而第一"),
                seg("乙", "", "学而第一"),
                seg("丙", "", "学而第一"),
                seg("", "译甲", "学而第一"),
                seg("", "译乙", "学而第一"),
                seg("", "译丙", "学而第一")));
        List<BookDocument.BookUnit> units = book.document().getChapters().get(0).units();
        // 合并发生在原文段位置（原文批 id 更小、即书本顺序），成书 3 个单元
        assertThat(units).hasSize(3);
        assertThat(units).extracting(BookDocument.BookUnit::source, BookDocument.BookUnit::translated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("甲", "译甲"),
                        org.assertj.core.groups.Tuple.tuple("乙", "译乙"),
                        org.assertj.core.groups.Tuple.tuple("丙", "译丙"));
        BookStats.ChapterStat st = book.stats().getChapters().get(0);
        assertThat(st.full()).isZero();
        assertThat(st.src()).isEqualTo(3);
        assertThat(st.dst()).isEqualTo(3);
        assertThat(st.paired()).isEqualTo(3);
        assertThat(st.warnings()).isEmpty();
        assertThat(book.stats().units()).isEqualTo(3);
        assertThat(book.stats().pairedUnits()).isEqualTo(3);
    }

    @Test
    void countMismatchLeavesPlaceholdersAndWarns() {
        AssembledBook book = assembler.assemble(List.of(
                seg("甲", "", "学而第一"),
                seg("乙", "", "学而第一"),
                seg("丙", "", "学而第一"),
                seg("", "译甲", "学而第一")));
        List<BookDocument.BookUnit> units = book.document().getChapters().get(0).units();
        assertThat(units).extracting(BookDocument.BookUnit::source, BookDocument.BookUnit::translated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("甲", "译甲"),
                        org.assertj.core.groups.Tuple.tuple("乙", null),
                        org.assertj.core.groups.Tuple.tuple("丙", null));
        assertThat(units.get(1).translatedSafe()).isEqualTo("〔未译〕");
        assertThat(units.get(1).sourceSafe()).isEqualTo("乙");
        List<String> warnings = book.stats().getChapters().get(0).warnings();
        assertThat(warnings).anyMatch(w -> w.contains("3") && w.contains("1"));
    }

    @Test
    void leftoverTranslationKeepsTextWithMissingSourcePlaceholder() {
        AssembledBook book = assembler.assemble(List.of(
                seg("", "只有译文", "学而第一")));
        List<BookDocument.BookUnit> units = book.document().getChapters().get(0).units();
        assertThat(units).hasSize(1);
        assertThat(units.get(0).source()).isNull();
        assertThat(units.get(0).sourceSafe()).isEqualTo("〔原文缺失〕");
        assertThat(units.get(0).translated()).isEqualTo("只有译文");
    }

    @Test
    void mixedFullAndSingleSideChapterWarnsManualReview() {
        AssembledBook book = assembler.assemble(List.of(
                seg("甲", "译甲", "学而第一"),   // 手工译好的完整段
                seg("乙", "", "学而第一"),       // 纯原文
                seg("", "译乙", "学而第一")));    // 纯译文
        assertThat(book.stats().getChapters().get(0).warnings())
                .anyMatch(w -> w.contains("人工核对"));
    }

    @Test
    void chaptersOrderByFirstAppearanceAndNullChapterGroups() {
        AssembledBook book = assembler.assemble(List.of(
                seg("一", "", "第一章"),
                seg("二", "", null),
                seg("三", "", "第二章"),
                seg("四", "", "第一章")));
        assertThat(book.document().getChapters()).extracting(BookDocument.BookChapter::title)
                .containsExactly("第一章", null, "第二章");
        assertThat(book.document().getChapters().get(0).units()).hasSize(2);
    }

    @Test
    void authorTranslatorTakeFirstPresentValue() {
        Segment a = seg("一", "", "第一章");
        a.setAuthor("孔子弟子");
        a.setTranslator(null);
        Segment b = seg("二", "", "第一章");
        b.setAuthor(null);
        b.setTranslator("James Legge");
        AssembledBook book = assembler.assemble(List.of(a, b));
        assertThat(book.document().getAuthor()).isEqualTo("孔子弟子");
        assertThat(book.document().getTranslator()).isEqualTo("James Legge");
    }

    @Test
    void blankTextTreatedAsMissing() {
        AssembledBook book = assembler.assemble(List.of(seg("甲", "  ", "学而第一")));
        List<BookDocument.BookUnit> units = book.document().getChapters().get(0).units();
        assertThat(units.get(0).translated()).isNull();
        assertThat(units.get(0).translatedSafe()).isEqualTo("〔未译〕");
    }

    @Test
    void emptyBookYieldsEmptyDocument() {
        AssembledBook book = assembler.assemble(new ArrayList<>());
        assertThat(book.document().getChapters()).isEmpty();
        assertThat(book.stats().units()).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/workspace/git/java/translation-database/backend && mvn test -Dtest=BookAssemblerTest`
Expected: 编译失败（`com.transdb.exporter` 类不存在）。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/transdb/exporter/BookDocument.java`：

```java
package com.transdb.exporter;

import java.util.List;

/** 成书渲染模型：装配结果，供各 BookRenderer 消费。 */
public record BookDocument(String title, String author, String translator,
                           List<BookChapter> chapters) {

    /** 书中的一个对齐单元：source/translated 为 null 表示该侧缺失（渲染时用占位符）。 */
    public record BookUnit(String source, String translated) {

        public static final String UNTRANSLATED_PLACEHOLDER = "〔未译〕";
        public static final String MISSING_SOURCE_PLACEHOLDER = "〔原文缺失〕";

        public String sourceSafe() {
            return source != null ? source : MISSING_SOURCE_PLACEHOLDER;
        }

        public String translatedSafe() {
            return translated != null ? translated : UNTRANSLATED_PLACEHOLDER;
        }
    }

    public record BookChapter(String title, List<BookUnit> units) {
    }
}
```

`backend/src/main/java/com/transdb/exporter/BookStats.java`：

```java
package com.transdb.exporter;

import java.util.List;

/** 导出预览统计：成书完成度 = pairedUnits / units（配对口径，非"有译文段/总段数"）。 */
public record BookStats(int segments, int units, int pairedUnits, List<ChapterStat> chapters) {

    public record ChapterStat(String title, int full, int src, int dst, int paired,
                              List<String> warnings) {
    }
}
```

`backend/src/main/java/com/transdb/exporter/AssembledBook.java`：

```java
package com.transdb.exporter;

public record AssembledBook(BookDocument document, BookStats stats) {
}
```

`backend/src/main/java/com/transdb/exporter/BookAssembler.java`：

```java
package com.transdb.exporter;

import com.transdb.domain.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整本书段 → 书稿模型的装配器。入参必须已按 id 升序（导入顺序即书本顺序）。
 *
 * 章节顺序 = 各章最小段 id 的先后（LinkedHashMap 保持首现顺序）；章内顺序 = 段 id。
 * 配对算法（同章内）：
 * 1. 就位——按 id 顺序生成占位单元：完整段 (src,dst)、纯原文段 (src,null)、纯译文段 (null,dst)；
 * 2. 拉链——第 i 个纯原文单元与第 i 个纯译文单元合并，合并结果落在原文单元的位置
 *    （原文侧导入的 id 必然更小，位置即书本顺序），被合并的译文单元移除。
 * 已知边界（设计文档 §3）：混源章（完整段 + 单侧段并存）时拉链位置可能错位，输出警告请人工核对。
 */
@Component
public class BookAssembler {

    public AssembledBook assemble(List<Segment> segments) {
        Map<String, List<Segment>> byChapter = new LinkedHashMap<>();
        for (Segment s : segments) {
            byChapter.computeIfAbsent(normalize(s.getChapter()), k -> new ArrayList<>()).add(s);
        }

        List<BookDocument.BookChapter> chapters = new ArrayList<>();
        List<BookStats.ChapterStat> chapterStats = new ArrayList<>();
        for (Map.Entry<String, List<Segment>> e : byChapter.entrySet()) {
            List<Segment> members = e.getValue();

            // 阶段一：按 id 就位
            List<BookDocument.BookUnit> placed = new ArrayList<>(members.size());
            int full = 0, src = 0, dst = 0;
            for (Segment m : members) {
                String s = normalize(m.getSourceText());
                String t = normalize(m.getTranslatedText());
                if (s != null && t != null) {
                    placed.add(new BookDocument.BookUnit(s, t));
                    full++;
                } else if (s != null) {
                    placed.add(new BookDocument.BookUnit(s, null));
                    src++;
                } else if (t != null) {
                    placed.add(new BookDocument.BookUnit(null, t));
                    dst++;
                }
                // 双空段（理论上不存在）：跳过，不计入任何类别
            }

            // 阶段二：拉链合并——纯原文第 i 个 × 纯译文第 i 个
            List<Integer> srcOnlyIdx = new ArrayList<>();
            List<Integer> dstOnlyIdx = new ArrayList<>();
            for (int i = 0; i < placed.size(); i++) {
                BookDocument.BookUnit u = placed.get(i);
                if (u.translated() == null && u.source() != null) {
                    srcOnlyIdx.add(i);
                } else if (u.source() == null) {
                    dstOnlyIdx.add(i);
                }
            }
            int paired = Math.min(srcOnlyIdx.size(), dstOnlyIdx.size());
            Map<Integer, String> fillTranslated = new HashMap<>();
            for (int k = 0; k < paired; k++) {
                fillTranslated.put(srcOnlyIdx.get(k), placed.get(dstOnlyIdx.get(k)).translated());
            }
            List<BookDocument.BookUnit> units = new ArrayList<>(placed.size() - paired);
            for (int i = 0; i < placed.size(); i++) {
                if (fillTranslated.containsKey(i)) {
                    units.add(new BookDocument.BookUnit(placed.get(i).source(), fillTranslated.get(i)));
                } else if (fillTranslated.containsValue(placed.get(i).translated())
                        && placed.get(i).source() == null && dstOnlyIdx.contains(i)) {
                    continue; // 已被合并进原文单元的译文占位单元，移除
                } else {
                    units.add(placed.get(i));
                }
            }

            chapters.add(new BookDocument.BookChapter(e.getKey(), units));
            chapterStats.add(new BookStats.ChapterStat(e.getKey(), full, src, dst, paired,
                    warnings(full, src, dst, paired)));
        }

        String title = segments.isEmpty() ? null : segments.get(0).getWorkTitle();
        BookDocument doc = new BookDocument(title,
                segments.stream().map(Segment::getAuthor).map(this::normalize).filter(a -> a != null).findFirst().orElse(null),
                segments.stream().map(Segment::getTranslator).map(this::normalize).filter(t -> t != null).findFirst().orElse(null),
                chapters);
        int units = chapters.stream().mapToInt(c -> c.units().size()).sum();
        int pairedUnits = chapters.stream().flatMap(c -> c.units().stream())
                .mapToInt(u -> u.source() != null && u.translated() != null ? 1 : 0).sum();
        return new AssembledBook(doc, new BookStats(segments.size(), units, pairedUnits, chapterStats));
    }

    private List<String> warnings(int full, int src, int dst, int paired) {
        List<String> ws = new ArrayList<>();
        if (src != dst && src > 0 && dst > 0) {
            ws.add("原文 %d 段、译文 %d 段，配对 %d 段，未配上 %d 段".formatted(
                    src, dst, paired, src + dst - 2 * paired));
        }
        if (full > 0 && (src > 0 || dst > 0)) {
            ws.add("本章混有已译段与单侧导入段，配对位置可能错位，请人工核对");
        }
        return ws;
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
```

注意上面"移除已合并译文单元"的分支判断有缺陷风险（`fillTranslated.containsValue` 可能误删文本相同的单元）。实现时改用显式集合：

```java
java.util.Set<Integer> mergedDstIdx = new java.util.HashSet<>();
for (int k = 0; k < paired; k++) {
    mergedDstIdx.add(dstOnlyIdx.get(k));
    fillTranslated.put(srcOnlyIdx.get(k), placed.get(dstOnlyIdx.get(k)).translated());
}
// 循环内改为：
} else if (mergedDstIdx.contains(i)) {
    continue;
```

（把这段修正直接落进最终代码，不要保留 containsValue 写法。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/workspace/git/java/translation-database/backend && mvn test -Dtest=BookAssemblerTest`
Expected: 9 个测试全 PASS。

- [ ] **Step 5: 检查点（不提交）**

确认 `mvn test -Dtest=BookAssemblerTest` 通过后本任务完成。改动全部保留在工作区，**不执行任何 git 命令**。

---

### Task 2: 导出枚举 + 三个渲染器（纯 JUnit TDD）

**Files:**
- Create: `backend/src/main/java/com/transdb/exporter/ExportMode.java`
- Create: `backend/src/main/java/com/transdb/exporter/ExportFormat.java`
- Create: `backend/src/main/java/com/transdb/exporter/render/BookRenderer.java`
- Create: `backend/src/main/java/com/transdb/exporter/render/TxtBookRenderer.java`
- Create: `backend/src/main/java/com/transdb/exporter/render/MarkdownBookRenderer.java`
- Create: `backend/src/main/java/com/transdb/exporter/render/DocxBookRenderer.java`
- Test: `backend/src/test/java/com/transdb/exporter/render/BookRenderersTest.java`

**Interfaces:**
- Consumes: Task 1 的 `BookDocument`（含 `BookUnit.sourceSafe()/translatedSafe()`）
- Produces:
  - `enum ExportMode { TRANSLATION_ONLY("译文"), BILINGUAL("对照"), SOURCE_ONLY("原文") }` + `String label()`
  - `enum ExportFormat { TXT, MARKDOWN, DOCX }`
  - `interface BookRenderer { ExportFormat format(); String contentType(); String extension(); byte[] render(BookDocument book, ExportMode mode); }`（三者均为 `@Component`，注入 `List<BookRenderer>` 按 format 选择——与 `DocumentParser` 同模式）
  - contentType：TXT `text/plain; charset=utf-8`、MD `text/markdown; charset=utf-8`、DOCX `application/vnd.openxmlformats-officedocument.wordprocessingml.document`；extension：`txt` / `md` / `docx`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/exporter/render/BookRenderersTest.java`：

```java
package com.transdb.exporter.render;

import com.transdb.exporter.BookDocument;
import com.transdb.exporter.BookStats;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookRenderersTest {

    private final TxtBookRenderer txt = new TxtBookRenderer();
    private final MarkdownBookRenderer md = new MarkdownBookRenderer();
    private final DocxBookRenderer docx = new DocxBookRenderer();

    private BookDocument sampleBook() {
        BookDocument.BookUnit paired = new BookDocument.BookUnit("学而时习之", "To learn and practise");
        BookDocument.BookUnit untranslated = new BookDocument.BookUnit("有朋自远方来", null);
        BookDocument.BookUnit noSource = new BookDocument.BookUnit(null, "orphan translation");
        return new BookDocument("论语", "孔子弟子", "James Legge", List.of(
                new BookDocument.BookChapter("学而第一", List.of(paired, untranslated)),
                new BookDocument.BookChapter("为政", List.of(noSource))));
    }

    @Test
    void txtStartsWithBomAndUsesCrlf() {
        byte[] bytes = txt.render(sampleBook(), ExportMode.BILINGUAL);
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).startsWith("论语");
        assertThat(text).contains("\r\n");
        assertThat(text).contains("学而时习之\r\nTo learn and practise");
        assertThat(text).contains("有朋自远方来\r\n〔未译〕");
        assertThat(text).contains("〔原文缺失〕");
        assertThat(text).contains("【学而第一】");
        assertThat(text).contains("【为政】");
    }

    @Test
    void txtTranslationOnlyOmitsSource() {
        String text = new String(txt.render(sampleBook(), ExportMode.TRANSLATION_ONLY),
                StandardCharsets.UTF_8);
        assertThat(text).contains("To learn and practise");
        assertThat(text).contains("〔未译〕");
        assertThat(text).doesNotContain("学而时习之");
    }

    @Test
    void txtSourceOnlyOmitsTranslation() {
        String text = new String(txt.render(sampleBook(), ExportMode.SOURCE_ONLY),
                StandardCharsets.UTF_8);
        assertThat(text).contains("学而时习之");
        assertThat(text).doesNotContain("To learn and practise");
    }

    @Test
    void txtSingleUntitledChapterHasNoChapterHeading() {
        BookDocument doc = new BookDocument("单章书", null, null, List.of(
                new BookDocument.BookChapter(null, List.of(
                        new BookDocument.BookUnit("唯一一段", "the only paragraph")))));
        String text = new String(txt.render(doc, ExportMode.BILINGUAL), StandardCharsets.UTF_8);
        assertThat(text).contains("唯一一段");
        assertThat(text).doesNotContain("【");
    }

    @Test
    void markdownUsesQuoteForSourceInBilingual() {
        String text = new String(md.render(sampleBook(), ExportMode.BILINGUAL),
                StandardCharsets.UTF_8);
        assertThat(text).startsWith("# 论语");
        assertThat(text).contains("## 学而第一");
        assertThat(text).contains("> 学而时习之");
        assertThat(text).contains("To learn and practise");
        assertThat(text).contains("> 〔原文缺失〕");
        assertThat(text).contains("**作者**：孔子弟子");
        assertThat(text).contains("**译者**：James Legge");
        assertThat(text).doesNotContain("\r");
    }

    @Test
    void docxContainsParagraphsInOrder() throws Exception {
        byte[] bytes = docx.render(sampleBook(), ExportMode.BILINGUAL);
        assertThat(bytes).startsWith('P', 'K'); // zip 魔数
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> ps = document.getParagraphs();
            List<String> texts = ps.stream().map(XWPFParagraph::getText).toList();
            assertThat(texts).contains("论语", "学而第一",
                    "学而时习之", "To learn and practise",
                    "有朋自远方来", "〔未译〕");
            assertThat(texts.indexOf("学而时习之")).isLessThan(texts.indexOf("To learn and practise"));
            // 对照模式：原文段灰色
            XWPFParagraph srcPara = ps.stream()
                    .filter(p -> "学而时习之".equals(p.getText())).findFirst().orElseThrow();
            assertThat(srcPara.getRuns()).isNotEmpty();
            assertThat(srcPara.getRuns().get(0).getColor()).isEqualTo("666666");
        }
    }

    @Test
    void docxTranslationOnlySkipsSourceParagraphs() throws Exception {
        byte[] bytes = docx.render(sampleBook(), ExportMode.TRANSLATION_ONLY);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> texts = document.getParagraphs().stream().map(XWPFParagraph::getText).toList();
            assertThat(texts).contains("To learn and practise");
            assertThat(texts).doesNotContain("学而时习之");
        }
    }

    @Test
    void rendererMetadataConsistent() {
        assertThat(txt.format()).isEqualTo(ExportFormat.TXT);
        assertThat(txt.contentType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(txt.extension()).isEqualTo("txt");
        assertThat(md.format()).isEqualTo(ExportFormat.MARKDOWN);
        assertThat(md.extension()).isEqualTo("md");
        assertThat(docx.format()).isEqualTo(ExportFormat.DOCX);
        assertThat(docx.extension()).isEqualTo("docx");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/workspace/git/java/translation-database/backend && mvn test -Dtest=BookRenderersTest`
Expected: 编译失败（枚举与渲染器不存在）。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/transdb/exporter/ExportMode.java`：

```java
package com.transdb.exporter;

/** 导出内容模式；label 用于导出文件名（书名-label.ext）。 */
public enum ExportMode {
    TRANSLATION_ONLY("译文"),
    BILINGUAL("对照"),
    SOURCE_ONLY("原文");

    private final String label;

    ExportMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
```

`backend/src/main/java/com/transdb/exporter/ExportFormat.java`：

```java
package com.transdb.exporter;

public enum ExportFormat {
    TXT,
    MARKDOWN,
    DOCX
}
```

`backend/src/main/java/com/transdb/exporter/render/BookRenderer.java`：

```java
package com.transdb.exporter.render;

import com.transdb.exporter.BookDocument;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;

/** 书稿 → 文件字节。实现类注册为 Spring Bean，按 format() 选择。 */
public interface BookRenderer {

    ExportFormat format();

    String contentType();

    String extension();

    byte[] render(BookDocument book, ExportMode mode);
}
```

`backend/src/main/java/com/transdb/exporter/render/TxtBookRenderer.java`：

```java
package com.transdb.exporter.render;

import com.transdb.exporter.BookDocument;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 纯文本书稿：UTF-8 带 BOM（防旧记事本乱码）、CRLF；对照模式原文与译文紧凑成对。 */
@Component
public class TxtBookRenderer implements BookRenderer {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Override
    public ExportFormat format() {
        return ExportFormat.TXT;
    }

    @Override
    public String contentType() {
        return "text/plain; charset=utf-8";
    }

    @Override
    public String extension() {
        return "txt";
    }

    @Override
    public byte[] render(BookDocument book, ExportMode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append(book.title()).append("\r\n");
        String byline = byline(book, "作者：", "译者：", "　");
        if (!byline.isEmpty()) {
            sb.append(byline).append("\r\n");
        }
        for (BookDocument.BookChapter ch : book.chapters()) {
            if (!singleUntitled(book, ch)) {
                sb.append("\r\n").append("【").append(ch.title() == null ? "正文" : ch.title()).append("】\r\n");
            }
            for (BookDocument.BookUnit u : ch.units()) {
                switch (mode) {
                    // 对照模式永远输出两行——缺失一侧用占位符（sourceSafe/translatedSafe 保证）
                    case BILINGUAL -> sb.append(u.sourceSafe()).append("\r\n")
                            .append(u.translatedSafe()).append("\r\n\r\n");
                    case TRANSLATION_ONLY -> sb.append(u.translatedSafe()).append("\r\n\r\n");
                    case SOURCE_ONLY -> sb.append(u.sourceSafe()).append("\r\n\r\n");
                }
            }
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    private boolean singleUntitled(BookDocument book, BookDocument.BookChapter ch) {
        return book.chapters().size() == 1 && ch.title() == null;
    }

    private String byline(BookDocument book, String authorPrefix, String translatorPrefix, String sep) {
        StringBuilder b = new StringBuilder();
        if (book.author() != null) {
            b.append(authorPrefix).append(book.author());
        }
        if (book.translator() != null) {
            if (b.length() > 0) {
                b.append(sep);
            }
            b.append(translatorPrefix).append(book.translator());
        }
        return b.toString();
    }
}
```

`backend/src/main/java/com/transdb/exporter/render/MarkdownBookRenderer.java`：

```java
package com.transdb.exporter.render;

import com.transdb.exporter.BookDocument;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Markdown 书稿：无 BOM、LF；对照模式原文用引用块（>）。 */
@Component
public class MarkdownBookRenderer implements BookRenderer {

    @Override
    public ExportFormat format() {
        return ExportFormat.MARKDOWN;
    }

    @Override
    public String contentType() {
        return "text/markdown; charset=utf-8";
    }

    @Override
    public String extension() {
        return "md";
    }

    @Override
    public byte[] render(BookDocument book, ExportMode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(book.title()).append("\n\n");
        if (book.author() != null || book.translator() != null) {
            if (book.author() != null) {
                sb.append("**作者**：").append(book.author());
            }
            if (book.translator() != null) {
                if (book.author() != null) {
                    sb.append("　");
                }
                sb.append("**译者**：").append(book.translator());
            }
            sb.append("\n\n");
        }
        for (BookDocument.BookChapter ch : book.chapters()) {
            if (!(book.chapters().size() == 1 && ch.title() == null)) {
                sb.append("## ").append(ch.title() == null ? "正文" : ch.title()).append("\n\n");
            }
            for (BookDocument.BookUnit u : ch.units()) {
                switch (mode) {
                    case BILINGUAL -> {
                        sb.append("> ").append(u.sourceSafe()).append("\n");
                        sb.append(u.translatedSafe()).append("\n\n");
                    }
                    case TRANSLATION_ONLY -> sb.append(u.translatedSafe()).append("\n\n");
                    case SOURCE_ONLY -> sb.append(u.sourceSafe()).append("\n\n");
                }
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
```

`backend/src/main/java/com/transdb/exporter/render/DocxBookRenderer.java`：

```java
package com.transdb.exporter.render;

import com.transdb.exporter.BookDocument;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Word 书稿（POI XWPF，已有依赖）：书名居中大字，章标题加粗，对照模式原文灰色小一号。 */
@Component
public class DocxBookRenderer implements BookRenderer {

    private static final String GRAY = "666666";

    @Override
    public ExportFormat format() {
        return ExportFormat.DOCX;
    }

    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override
    public String extension() {
        return "docx";
    }

    @Override
    public byte[] render(BookDocument book, ExportMode mode) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            paragraph(doc, book.title(), 22, true, null, ParagraphAlignment.CENTER, 240);
            String byline = joinByline(book);
            if (!byline.isEmpty()) {
                paragraph(doc, byline, 11, false, GRAY, ParagraphAlignment.CENTER, 240);
            }
            for (BookDocument.BookChapter ch : book.chapters()) {
                if (!(book.chapters().size() == 1 && ch.title() == null)) {
                    paragraph(doc, ch.title() == null ? "正文" : ch.title(), 16, true, null, null, 160);
                }
                for (BookDocument.BookUnit u : ch.units()) {
                    switch (mode) {
                        case BILINGUAL -> {
                            paragraph(doc, u.sourceSafe(), 11, false, GRAY, null, 40);
                            paragraph(doc, u.translatedSafe(), 12, false, null, null, 160);
                        }
                        case TRANSLATION_ONLY -> paragraph(doc, u.translatedSafe(), 12, false, null, null, 160);
                        case SOURCE_ONLY -> paragraph(doc, u.sourceSafe(), 12, false, null, null, 160);
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("docx 生成失败", e);
        }
    }

    private String joinByline(BookDocument book) {
        StringBuilder b = new StringBuilder();
        if (book.author() != null) {
            b.append("作者：").append(book.author());
        }
        if (book.translator() != null) {
            if (b.length() > 0) {
                b.append("　");
            }
            b.append("译者：").append(book.translator());
        }
        return b.toString();
    }

    private void paragraph(XWPFDocument doc, String text, int fontSize, boolean bold,
                           String color, ParagraphAlignment align, int spacingAfter) {
        XWPFParagraph p = doc.createParagraph();
        if (align != null) {
            p.setAlignment(align);
        }
        p.setSpacingAfter(spacingAfter);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(fontSize);
        run.setBold(bold);
        if (color != null) {
            run.setColor(color);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/workspace/git/java/translation-database/backend && mvn test -Dtest=BookRenderersTest`
Expected: 8 个测试全 PASS。

- [ ] **Step 5: 检查点（不提交）**

改动保留工作区，不执行任何 git 命令。

---

### Task 3: 错误码 + 仓库查询 + ExportService + ExportController（集成测试 TDD）

**Files:**
- Modify: `backend/src/main/java/com/transdb/common/ErrorCode.java`（追加 `EXPORT_WORK_NOT_FOUND`，放 `ACCESS_DENIED` 之后）
- Modify: `backend/src/main/java/com/transdb/repository/SegmentRepository.java`（2 个查询 + 投影接口）
- Create: `backend/src/main/java/com/transdb/dto/ExportWorksItemVO.java`
- Create: `backend/src/main/java/com/transdb/dto/ExportPreviewVO.java`
- Create: `backend/src/main/java/com/transdb/dto/ExportRequest.java`
- Create: `backend/src/main/java/com/transdb/exporter/ExportedFile.java`
- Create: `backend/src/main/java/com/transdb/exporter/ExportService.java`
- Create: `backend/src/main/java/com/transdb/controller/ExportController.java`
- Test: `backend/src/test/java/com/transdb/controller/ExportControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `BookAssembler/AssembledBook/BookStats`；Task 2 `BookRenderer/ExportMode/ExportFormat`；现有 `ApiResponse.ok(data)`、`BusinessException.of(ErrorCode, String)`、`AbstractIntegrationTest`（`createUser(Role)`、`bearer(SysUser)`、`TestRestTemplate rest`、`JwtService`、`SysUserRepository`）。
- Produces:
  - `record ExportWorksItemVO(String workTitle, long chapters, long totalSegments, long translatedSegments)`
  - `record ExportPreviewVO(long segments, int units, int pairedUnits, List<ChapterStatVO> chapters)` + 嵌套 `record ChapterStatVO(String title, int full, int src, int dst, int paired, List<String> warnings)`
  - `record ExportRequest(String workTitle, String mode, String format)`
  - `record ExportedFile(String filename, String contentType, byte[] content)`
  - `ExportService`：`List<ExportWorksItemVO> listWorks()`、`ExportPreviewVO preview(String workTitle)`、`ExportedFile generate(String workTitle, ExportMode mode, ExportFormat format)`（均 `@Transactional(readOnly = true)`）
  - `SegmentRepository` 新增：`List<Segment> findByWorkTitleOrderByIdAsc(String workTitle)`、`List<WorkStatProjection> aggregateWorkStats()`（投影 `getWorkTitle()/getTotalSegments()/getTranslatedSegments()/getChapters()`）
  - 端点：`GET /api/v1/export/works`、`POST /api/v1/export/preview`、`POST /api/v1/export`（均 EDITOR+；最后一个返回 `ResponseEntity<byte[]>` 附件）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/controller/ExportControllerTest.java`（继承 `AbstractIntegrationTest`，需 Docker；书名全部带 nanoTime 后缀保证唯一）：

```java
package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.common.ContentHash;
import com.transdb.domain.Role;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.domain.SysUser;
import com.transdb.repository.SegmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportControllerTest extends AbstractIntegrationTest {

    @Autowired
    private SegmentRepository segmentRepository;

    private final String work = "导出测试书" + System.nanoTime();

    private void seg(SysUser owner, String src, String dst, String chapter) {
        Segment s = new Segment();
        s.setSourceText(src);
        s.setTranslatedText(dst == null ? "" : dst);
        s.setWorkTitle(work);
        s.setChapter(chapter);
        s.setStatus(SegmentStatus.DRAFT);
        s.setCreatedBy(owner);
        s.setContentHash(ContentHash.sha256(src, dst == null ? "" : dst) + "-" + System.nanoTime());
        segmentRepository.saveAndFlush(s);
    }

    private HttpEntity<Map<String, String>> json(SysUser user, Map<String, String> body) {
        HttpHeaders h = bearer(user);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    @Test
    void anonymousAndViewerAreRejected() {
        ResponseEntity<String> anon = rest.getForEntity("/api/v1/export/works", String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        SysUser viewer = createUser(Role.VIEWER);
        ResponseEntity<String> viewerRes =
                rest.exchange("/api/v1/export/works", org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(bearer(viewer)), String.class);
        assertThat(viewerRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewerRes.getBody()).contains("9004");
    }

    @Test
    void worksListAggregatesByWorkTitle() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "一", "t1", "第一章");
        seg(editor, "二", "t2", "第一章");
        seg(editor, "三", "", "第二章");

        ResponseEntity<String> res =
                rest.exchange("/api/v1/export/works", org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(bearer(editor)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(work);
        assertThat(res.getBody()).contains("\"totalSegments\":3");
        assertThat(res.getBody()).contains("\"translatedSegments\":2");
        assertThat(res.getBody()).contains("\"chapters\":2");
    }

    @Test
    void previewPairsSplitSideSegments() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "甲", null, "学而第一");
        seg(editor, "乙", null, "学而第一");
        seg(editor, "", "译甲", "学而第一");
        seg(editor, "", "译乙", "学而第一");

        ResponseEntity<String> res = rest.postForEntity("/api/v1/export/preview",
                json(editor, Map.of("workTitle", work)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"units\":2");
        assertThat(res.getBody()).contains("\"pairedUnits\":2");
        assertThat(res.getBody()).contains("\"src\":2");
        assertThat(res.getBody()).contains("\"dst\":2");
        assertThat(res.getBody()).contains("\"paired\":2");
    }

    @Test
    void previewWarnsOnCountMismatch() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "甲", null, "学而第一");
        seg(editor, "甲2", null, "学而第一");
        seg(editor, "", "译甲", "学而第一");

        ResponseEntity<String> res = rest.postForEntity("/api/v1/export/preview",
                json(editor, Map.of("workTitle", work)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("未配上");
    }

    @Test
    void downloadBilingualTxtHasBomAndBothTexts() {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "学而时习之", "To learn and practise", "学而第一");

        ResponseEntity<byte[]> res = rest.postForEntity("/api/v1/export",
                json(editor, Map.of("workTitle", work, "mode", "BILINGUAL", "format", "TXT")), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).hasToString("text/plain;charset=UTF-8");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        byte[] body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8))
                .contains("学而时习之")
                .contains("To learn and practise");
    }

    @Test
    void downloadDocxIsZipAndTranslationOnlyDropsSource() throws Exception {
        SysUser editor = createUser(Role.EDITOR);
        seg(editor, "学而时习之", "To learn and practise", "学而第一");

        ResponseEntity<byte[]> res = rest.postForEntity("/api/v1/export",
                json(editor, Map.of("workTitle", work, "mode", "TRANSLATION_ONLY", "format", "DOCX")), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains(".docx");
        byte[] body = res.getBody();
        assertThat(body).isNotNull().startsWith('P', 'K');
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(body))) {
            var texts = doc.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText).toList();
            assertThat(texts).contains("To learn and practise");
            assertThat(texts).doesNotContain("学而时习之");
        }
    }

    @Test
    void unknownWorkRejected6001() {
        SysUser editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.postForEntity("/api/v1/export/preview",
                json(editor, Map.of("workTitle", "不存在的书" + System.nanoTime())), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("6001");
    }
}
```

注意：`bearer(SysUser)` 返回 `HttpHeaders`（沿用 `AbstractIntegrationTest` 既有签名；若实际返回其他类型，以现有 `ImportControllerTest` 的用法为准适配）。`contentHash` 追加 nanoTime 防止与库内既有数据撞哈希触发唯一约束（若库中该列无唯一约束则无碍，保留后缀亦无害）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/workspace/git/java/translation-database/backend && mvn test -Dtest=ExportControllerTest`
Expected: 编译失败（ErrorCode/查询/DTO/Service/Controller 不存在）。（本步需 Docker 已启动。）

- [ ] **Step 3: 写实现**

`ErrorCode.java` 在 `ACCESS_DENIED` 之前（即 `REINDEX_ALREADY_RUNNING` 与 `USERNAME_EXISTS` 所属段之后、文件末尾枚举列表里）追加：

```java
,
EXPORT_WORK_NOT_FOUND(HttpStatus.NOT_FOUND, 6001, "该书名不存在或没有可导出的内容")
```

（保持既有逗号风格：前一个值结尾加逗号，新值成为最后一项、以 `;` 收尾——按文件现状最小改动。）

`SegmentRepository.java` 追加：

```java
List<Segment> findByWorkTitleOrderByIdAsc(String workTitle);

@Query("select s.workTitle as workTitle, count(s) as totalSegments, " +
        "sum(case when s.translatedText is not null and s.translatedText <> '' then 1 else 0 end) as translatedSegments, " +
        "count(distinct s.chapter) as chapters " +
        "from Segment s where s.workTitle is not null group by s.workTitle order by s.workTitle")
List<WorkStatProjection> aggregateWorkStats();

interface WorkStatProjection {
    String getWorkTitle();

    long getTotalSegments();

    long getTranslatedSegments();

    long getChapters();
}
```

`backend/src/main/java/com/transdb/dto/ExportWorksItemVO.java`：

```java
package com.transdb.dto;

/** 成书导出·书单行。 */
public record ExportWorksItemVO(String workTitle, long chapters, long totalSegments, long translatedSegments) {
}
```

`backend/src/main/java/com/transdb/dto/ExportPreviewVO.java`：

```java
package com.transdb.dto;

import java.util.List;

/** 成书导出·配对预览；完成度 = pairedUnits / units（配对口径）。 */
public record ExportPreviewVO(long segments, int units, int pairedUnits, List<ChapterStatVO> chapters) {

    public record ChapterStatVO(String title, int full, int src, int dst, int paired, List<String> warnings) {
    }
}
```

`backend/src/main/java/com/transdb/dto/ExportRequest.java`：

```java
package com.transdb.dto;

/** mode/format 用字符串接收、控制器内解析，非法值给友好错误（与 ImportTextRole 同惯例）。 */
public record ExportRequest(String workTitle, String mode, String format) {
}
```

`backend/src/main/java/com/transdb/exporter/ExportedFile.java`：

```java
package com.transdb.exporter;

public record ExportedFile(String filename, String contentType, byte[] content) {
}
```

`backend/src/main/java/com/transdb/exporter/ExportService.java`：

```java
package com.transdb.exporter;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ExportPreviewVO;
import com.transdb.dto.ExportWorksItemVO;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.SegmentRepository.WorkStatProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final SegmentRepository segmentRepository;
    private final BookAssembler assembler;
    private final List<BookRendererHolder> rendererHolders; // 见下：直接用 List<BookRenderer> 即可

    @Transactional(readOnly = true)
    public List<ExportWorksItemVO> listWorks() {
        return segmentRepository.aggregateWorkStats().stream()
                .map(p -> new ExportWorksItemVO(p.getWorkTitle(), p.getChapters(),
                        p.getTotalSegments(), p.getTranslatedSegments()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExportPreviewVO preview(String workTitle) {
        BookStats s = assembleBook(workTitle).stats();
        return new ExportPreviewVO(s.segments(), s.units(), s.pairedUnits(),
                s.chapters().stream()
                        .map(c -> new ExportPreviewVO.ChapterStatVO(c.title(), c.full(), c.src(),
                                c.dst(), c.paired(), c.warnings()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public ExportedFile generate(String workTitle, ExportMode mode, ExportFormat format) {
        AssembledBook book = assembleBook(workTitle);
        com.transdb.exporter.render.BookRenderer renderer = renderers.stream()
                .filter(r -> r.format() == format).findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.VALIDATION_FAILED,
                        "不支持的导出格式: " + format));
        String filename = sanitize(book.document().title()) + "-" + mode.label() + "." + renderer.extension();
        return new ExportedFile(filename, renderer.contentType(),
                renderer.render(book.document(), mode));
    }

    private AssembledBook assembleBook(String workTitle) {
        if (workTitle == null || workTitle.isBlank()) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "workTitle 不能为空");
        }
        List<com.transdb.domain.Segment> segments =
                segmentRepository.findByWorkTitleOrderByIdAsc(workTitle.strip());
        if (segments.isEmpty()) {
            throw BusinessException.of(ErrorCode.EXPORT_WORK_NOT_FOUND);
        }
        return assembler.assemble(segments);
    }

    private String sanitize(String name) {
        return name == null ? "book" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
```

上面字段笔误修正（落码时用这个版本，不要 `BookRendererHolder`）：

```java
private final SegmentRepository segmentRepository;
private final BookAssembler assembler;
private final List<com.transdb.exporter.render.BookRenderer> renderers;
```

`backend/src/main/java/com/transdb/controller/ExportController.java`：

```java
package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ExportPreviewVO;
import com.transdb.dto.ExportRequest;
import com.transdb.dto.ExportWorksItemVO;
import com.transdb.exporter.ExportFormat;
import com.transdb.exporter.ExportMode;
import com.transdb.exporter.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @GetMapping("/works")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<List<ExportWorksItemVO>> works() {
        return ApiResponse.ok(exportService.listWorks());
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<ExportPreviewVO> preview(@RequestBody ExportRequest req) {
        return ApiResponse.ok(exportService.preview(req.workTitle()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest req) {
        ExportedFileLocal file = doExport(req);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20"))
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .body(file.content());
    }

    private ExportedFileLocal doExport(ExportRequest req) {
        ExportMode mode = parseMode(req.mode());
        ExportFormat format = parseFormat(req.format());
        com.transdb.exporter.ExportedFile f = exportService.generate(req.workTitle(), mode, format);
        return new ExportedFileLocal(f.filename(), f.contentType(), f.content());
    }

    private record ExportedFileLocal(String filename, String contentType, byte[] content) {
    }

    private ExportMode parseMode(String raw) {
        try {
            return ExportMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "mode 只支持 TRANSLATION_ONLY（仅译文）/ BILINGUAL（对照）/ SOURCE_ONLY（仅原文）");
        }
    }

    private ExportFormat parseFormat(String raw) {
        try {
            return ExportFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED,
                    "format 只支持 TXT / MARKDOWN / DOCX");
        }
    }
}
```

（落码时把 `ExportedFileLocal` 中转去掉、直接用 `ExportedFile`，上面仅为避免 import 歧义的示意；最终 `doExport` 返回 `com.transdb.exporter.ExportedFile`。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/workspace/git/java/translation-database/backend && mvn test -Dtest=ExportControllerTest`
Expected: 7 个测试全 PASS（需 Docker）。

- [ ] **Step 5: 检查点（不提交）**

跑全量后端测试确认无回退：`mvn -f /e/workspace/git/java/translation-database/backend/pom.xml test`。改动保留工作区，不执行任何 git 命令。

---

### Task 4: 前端 API 层——http.ts blob 旁路 + 导出接口（vitest TDD）

**Files:**
- Modify: `frontend/src/api/http.ts`（成功拦截器 blob 旁路 + 错误分支 Blob 消息解析）
- Modify: `frontend/src/api/index.ts`（3 个 interface + 3 个 api 方法）
- Test: `frontend/src/tests/http.spec.ts`（追加 blob 用例）

**Interfaces:**
- Consumes: 现有 axios 实例（`baseURL '/api/v1'`、token 注入、ApiResponse 解包）
- Produces:
  - `http.ts` 成功拦截器：`resp.config.responseType === 'blob'` 时直接 `return resp.data`（不解包）；错误拦截器：`error.response.data instanceof Blob` 时 `await data.text()` + `JSON.parse` 提取 `code/message` 再走既有 401/ElMessage 逻辑
  - `api/index.ts`：
    - `interface ExportWorkItem { workTitle: string; chapters: number; totalSegments: number; translatedSegments: number }`
    - `interface ExportChapterStat { title: string | null; full: number; src: number; dst: number; paired: number; warnings: string[] }`
    - `interface ExportPreview { segments: number; units: number; pairedUnits: number; chapters: ExportChapterStat[] }`
    - `api.exportWorks(): Promise<ExportWorkItem[]>`、`api.exportPreview(workTitle: string): Promise<ExportPreview>`、`api.exportBook(workTitle, mode: 'TRANSLATION_ONLY'|'BILINGUAL'|'SOURCE_ONLY', format: 'TXT'|'MARKDOWN'|'DOCX'): Promise<Blob>`

- [ ] **Step 1: 写失败测试**

`frontend/src/tests/http.spec.ts` 追加（放在既有 `describe` 之后；同时给既有 `getOnError` 旁补 `getOnSuccess`）：

```ts
function getOnSuccess() {
  const instance = (axios as any).create.mock.results[0].value
  return instance.interceptors.response.use.mock.calls[0][0] as (resp: unknown) => unknown
}

describe('http 响应拦截器 blob 分支', () => {
  beforeEach(() => {
    expect(http).toBeDefined()
    setActivePinia(createPinia())
    localStorage.clear()
    vi.spyOn(ElMessage, 'error').mockClear()
  })

  it('blob 响应不解包 ApiResponse，直接返回 Blob 本体', () => {
    const blob = new Blob(['PK-bytes'])
    expect(getOnSuccess()({ config: { responseType: 'blob' }, data: blob })).toBe(blob)
  })

  it('非 blob 成功响应仍解包 data 字段', () => {
    const resp = { config: {}, data: { code: 0, message: 'ok', data: 42 } }
    expect(getOnSuccess()(resp)).toBe(42)
  })

  it('blob 形态的错误响应能解析出后端 message', async () => {
    const err = {
      response: {
        status: 404,
        data: new Blob([JSON.stringify({ code: 6001, message: '该书名不存在或没有可导出的内容' })], {
          type: 'application/json'
        })
      }
    }
    await expect(getOnError()(err)).rejects.toBe(err)
    expect(ElMessage.error).toHaveBeenCalledWith('该书名不存在或没有可导出的内容')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/workspace/git/java/translation-database/frontend && npx vitest run src/tests/http.spec.ts`
Expected: 新增 3 个用例 FAIL（blob 分支不存在：`getOnSuccess` 返回 `body.data` = undefined / Blob 分支走通用网络错误文案）。

- [ ] **Step 3: 写实现**

`frontend/src/api/http.ts` 成功分支改为：

```ts
http.interceptors.response.use(
  (resp) => {
    // 文件下载（blob）不解包 ApiResponse，直接把 Blob 交给调用方
    if (resp.config?.responseType === 'blob') {
      return resp.data
    }
    const body = resp.data
    if (body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body.data
  },
  async (error) => {
    const status = error.response?.status
    let code = error.response?.data?.code
    let message = error.response?.data?.message
    // blob 请求的业务错误：响应体是 Blob 包着的 JSON，解出来拿消息
    if (error.response?.data instanceof Blob) {
      try {
        const parsed = JSON.parse(await error.response.data.text())
        code = parsed.code
        message = parsed.message
      } catch {
        /* 解析失败走兜底文案 */
      }
    }
    if (status === 401) {
      localStorage.removeItem('transdb_token')
      localStorage.removeItem('transdb_user')
      import('../stores/auth').then(({ useAuthStore }) => {
        useAuthStore().clear()
      })
      if (code !== 1001 && !location.pathname.startsWith('/login')) {
        location.href = '/login'
      } else {
        ElMessage.error(message || '登录失败')
      }
    } else {
      ElMessage.error(message || '网络错误')
    }
    return Promise.reject(error)
  }
)
```

`frontend/src/api/index.ts` 类型区追加：

```ts
export interface ExportWorkItem {
  workTitle: string
  chapters: number
  totalSegments: number
  translatedSegments: number
}

export interface ExportChapterStat {
  title: string | null
  full: number
  src: number
  dst: number
  paired: number
  warnings: string[]
}

export interface ExportPreview {
  segments: number
  units: number
  pairedUnits: number
  chapters: ExportChapterStat[]
}

export type ExportMode = 'TRANSLATION_ONLY' | 'BILINGUAL' | 'SOURCE_ONLY'
export type ExportFormat = 'TXT' | 'MARKDOWN' | 'DOCX'
```

`api` 对象追加（与既有方法并列）：

```ts
exportWorks: () => http.get<never, ExportWorkItem[]>('/export/works'),
exportPreview: (workTitle: string) => http.post<never, ExportPreview>('/export/preview', { workTitle }),
exportBook: (workTitle: string, mode: ExportMode, format: ExportFormat) =>
  http.post<never, Blob>('/export', { workTitle, mode, format }, { responseType: 'blob' }),
```

（import 类型：`ExportMode/ExportFormat` 从 api/index.ts 导出使用。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/workspace/git/java/translation-database/frontend && npm run test`
Expected: 全部 PASS（既有 6 个 spec 文件 + 新增用例）。

- [ ] **Step 5: 检查点（不提交）**

改动保留工作区，不执行任何 git 命令。

---

### Task 5: 路由 + 导航 + 「成书导出」页面 + README

**Files:**
- Modify: `frontend/src/router/index.ts`（`/export` 路由 + EDITOR 守卫）
- Modify: `frontend/src/components/AppLayout.vue`（导航按钮「成书导出」，`v-if="auth.isEditor"`，`Reading` 图标）
- Create: `frontend/src/views/ExportView.vue`
- Test: `frontend/src/tests/export-view.spec.ts`
- Modify: `README.md`（API 表追加导出行）

**Interfaces:**
- Consumes: Task 4 的 `api.exportWorks/exportPreview/exportBook` 与类型；`useAuthStore().isEditor`；既有路由守卫写法（`to.path.startsWith('/admin') && !auth.isAdmin`）。
- Produces: 路由 `/export`（未登录→login；非 EDITOR→`/`）；导航项；页面组件 `ExportView.vue`。

- [ ] **Step 1: 写失败测试**

`frontend/src/tests/export-view.spec.ts`：

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import ExportView from '../views/ExportView.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    exportWorks: vi.fn().mockResolvedValue([
      { workTitle: '论语', chapters: 2, totalSegments: 10, translatedSegments: 6 },
      { workTitle: '孟子', chapters: 1, totalSegments: 4, translatedSegments: 4 }
    ]),
    exportPreview: vi.fn().mockResolvedValue({
      segments: 10,
      units: 8,
      pairedUnits: 6,
      chapters: [
        { title: '学而第一', full: 4, src: 4, dst: 2, paired: 2, warnings: ['原文 4 段、译文 2 段，配对 2 段，未配上 4 段'] }
      ]
    }),
    exportBook: vi.fn().mockResolvedValue(new Blob(['mock-file']))
  }
}))

import { api as realApi } from '../api'
const mockApi = vi.mocked(realApi)

describe('ExportView 成书导出页', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // jsdom 无 createObjectURL，打桩
    Object.assign(URL, {
      createObjectURL: vi.fn(() => 'blob:mock'),
      revokeObjectURL: vi.fn()
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('挂载后展示书单', async () => {
    const wrapper = mount(ExportView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(mockApi.exportWorks).toHaveBeenCalled()
    expect(wrapper.text()).toContain('论语')
    expect(wrapper.text()).toContain('孟子')
  })

  it('点选书籍后加载预览并展示配对统计与警告', async () => {
    const wrapper = mount(ExportView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="work-card"]').trigger('click')
    await flushPromises()
    expect(mockApi.exportPreview).toHaveBeenCalledWith('论语')
    expect(wrapper.text()).toContain('学而第一')
    expect(wrapper.text()).toContain('未配上')
  })

  it('点击导出触发下载', async () => {
    const wrapper = mount(ExportView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="work-card"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="download-btn"]').trigger('click')
    await flushPromises()
    expect(mockApi.exportBook).toHaveBeenCalledWith('论语', 'BILINGUAL', 'DOCX')
    expect(URL.createObjectURL).toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/workspace/git/java/translation-database/frontend && npx vitest run src/tests/export-view.spec.ts`
Expected: FAIL（`../views/ExportView.vue` 不存在）。

- [ ] **Step 3: 写实现**

`frontend/src/router/index.ts`——routes 数组 `/import` 之后追加：

```ts
{ path: '/export', component: () => import('../views/ExportView.vue') },
```

守卫 `beforeEach` 里 admin 判断之后追加：

```ts
if (to.path === '/export' && !auth.isEditor) {
  return { path: '/' }
}
```

`frontend/src/components/AppLayout.vue`——「录入资料」按钮之后追加同款按钮（图标 `Reading` 需加入该文件顶部的 `@element-plus/icons-vue` 具名导入）：

```vue
<button
  v-if="auth.isEditor"
  class="nav-item"
  :class="{ active: route.path === '/export' }"
  type="button"
  @click="$router.push('/export')"
>
  <el-icon><Reading /></el-icon>
  <span>成书导出</span>
</button>
```

`frontend/src/views/ExportView.vue`（新建；样式沿用页面既有 class 命名习惯，主题变量见 `styles/theme.css`，可按 SearchView/ImportView 的观感微调）：

```vue
<template>
  <div class="export-page">
    <header class="page-header">
      <h1>成书导出</h1>
      <p class="subtitle">翻译完成后，把整本书按原始顺序合并成书稿文件</p>
    </header>

    <div v-loading="loading" class="work-grid">
      <div
        v-for="w in works"
        :key="w.workTitle"
        class="work-card"
        data-test="work-card"
        @click="openPreview(w)"
      >
        <div class="work-title">{{ w.workTitle }}</div>
        <div class="work-meta">{{ w.chapters }} 章 · {{ w.totalSegments }} 段 · 已译 {{ w.translatedSegments }} 段</div>
      </div>
      <p v-if="!loading && works.length === 0" class="empty-tip">
        还没有可导出的书——先在「录入资料」里导入整本书，或给句段填写书名。
      </p>
    </div>

    <el-dialog v-model="dialogVisible" :title="`导出《${selected?.workTitle ?? ''}》`" width="640px">
      <template v-if="preview">
        <div class="progress-row">
          <span>成书完成度（配对口径）</span>
          <el-progress
            :percentage="percentage"
            :status="percentage === 100 ? 'success' : undefined"
          />
        </div>
        <el-radio-group v-model="mode" class="option-row">
          <el-radio-button value="BILINGUAL">原文译文对照</el-radio-button>
          <el-radio-button value="TRANSLATION_ONLY">仅译文</el-radio-button>
          <el-radio-button value="SOURCE_ONLY">仅原文</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="format" class="option-row">
          <el-radio-button value="DOCX">Word</el-radio-button>
          <el-radio-button value="MARKDOWN">Markdown</el-radio-button>
          <el-radio-button value="TXT">TXT</el-radio-button>
        </el-radio-group>
        <el-table :data="preview.chapters" size="small" max-height="260">
          <el-table-column prop="title" label="章节" width="140">
            <template #default="{ row }">{{ row.title ?? '（无章节）' }}</template>
          </el-table-column>
          <el-table-column prop="full" label="完整" width="60" />
          <el-table-column prop="src" label="纯原文" width="70" />
          <el-table-column prop="dst" label="纯译文" width="70" />
          <el-table-column prop="paired" label="配对" width="60" />
          <el-table-column label="提示">
            <template #default="{ row }">
              <span v-for="w in row.warnings" :key="w" class="warning-text">{{ w }}</span>
              <span v-if="row.warnings.length === 0" class="ok-text">齐整</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <div v-else v-loading="previewLoading" class="preview-loading" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-test="download-btn"
          :loading="downloading"
          :disabled="!preview"
          @click="download"
        >
          导出下载
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  api,
  type ExportFormat,
  type ExportMode,
  type ExportPreview,
  type ExportWorkItem
} from '../api'

const works = ref<ExportWorkItem[]>([])
const loading = ref(false)
const selected = ref<ExportWorkItem | null>(null)
const preview = ref<ExportPreview | null>(null)
const previewLoading = ref(false)
const dialogVisible = ref(false)
const downloading = ref(false)
const mode = ref<ExportMode>('BILINGUAL')
const format = ref<ExportFormat>('DOCX')

const percentage = computed(() => {
  if (!preview.value || preview.value.units === 0) return 0
  return Math.round((preview.value.pairedUnits / preview.value.units) * 100)
})

onMounted(async () => {
  loading.value = true
  try {
    works.value = await api.exportWorks()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
})

async function openPreview(w: ExportWorkItem) {
  selected.value = w
  preview.value = null
  dialogVisible.value = true
  previewLoading.value = true
  try {
    preview.value = await api.exportPreview(w.workTitle)
  } catch {
    /* 拦截器已提示 */
  } finally {
    previewLoading.value = false
  }
}

async function download() {
  if (!selected.value) return
  downloading.value = true
  try {
    const blob = await api.exportBook(selected.value.workTitle, mode.value, format.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${selected.value.workTitle}-${modeLabel(mode.value)}.${ext(format.value)}`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    /* 拦截器已提示 */
  } finally {
    downloading.value = false
  }
}

function modeLabel(m: ExportMode): string {
  return m === 'BILINGUAL' ? '对照' : m === 'TRANSLATION_ONLY' ? '译文' : '原文'
}

function ext(f: ExportFormat): string {
  return f === 'DOCX' ? 'docx' : f === 'MARKDOWN' ? 'md' : 'txt'
}
</script>

<style scoped>
.export-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

.page-header h1 {
  margin: 0 0 4px;
}

.subtitle {
  margin: 0 0 20px;
  color: var(--el-text-color-secondary, #909399);
}

.work-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  min-height: 120px;
}

.work-card {
  border: 1px solid var(--el-border-color-light, #dcdfe6);
  border-radius: 8px;
  padding: 16px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.work-card:hover {
  box-shadow: var(--el-box-shadow-light);
}

.work-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 6px;
}

.work-meta {
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}

.empty-tip {
  grid-column: 1 / -1;
  color: var(--el-text-color-secondary, #909399);
}

.progress-row {
  margin-bottom: 12px;
}

.option-row {
  display: flex;
  margin-bottom: 12px;
}

.warning-text {
  color: var(--el-color-warning);
  font-size: 12px;
  display: block;
}

.ok-text {
  color: var(--el-color-success);
  font-size: 12px;
}

.preview-loading {
  min-height: 200px;
}
</style>
```

`README.md`——API 表（现有 `/api/v1/segments/import/document` 行之后）追加一行，列数与邻行一致：

```markdown
| GET /api/v1/export/works、POST /api/v1/export/preview、POST /api/v1/export | 成书导出（书单统计/配对预览/文件下载；TXT/Markdown/Word × 仅译文/对照/仅原文） | EDITOR+ |
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/workspace/git/java/translation-database/frontend && npm run test`
Expected: 全部 PASS（含新 export-view.spec.ts 3 个用例）。

- [ ] **Step 5: 类型与构建检查**

Run: `cd /e/workspace/git/java/translation-database/frontend && npm run build`
Expected: `vue-tsc -b && vite build` 成功无类型错误。

- [ ] **Step 6: 检查点（不提交）**

改动保留工作区，不执行任何 git 命令。

---

### Task 6: 全量验证

**Files:** 无新文件（纯验证）。

**Interfaces:**
- Consumes: Task 1-5 全部产出。

- [ ] **Step 1: 后端全量测试**

Run: `cd /e/workspace/git/java/translation-database/backend && mvn test`
Expected: 全部 PASS（既有测试 + 新增 BookAssemblerTest 9 / BookRenderersTest 8 / ExportControllerTest 7）。若 Docker 不可用导致 Testcontainers 用例失败：记录具体报错并至少确认 3 个纯 JUnit 新测试类通过，如实汇报。

- [ ] **Step 2: 前端全量测试与构建**

Run: `cd /e/workspace/git/java/translation-database/frontend && npm run test && npm run build`
Expected: 全部 PASS、构建成功。

- [ ] **Step 3: 冒烟（可选但推荐，需 Docker Compose 环境）**

Run: `docker compose up -d` 后 `cd frontend && npm run dev`，浏览器以 EDITOR 账号登录 → 「成书导出」→ 选书 → 预览 → 三种格式各下载一次人工检查（TXT 记事本打开不乱码、DOCX 能用 Word 打开、对照排版正确）。

- [ ] **Step 4: 收尾检查点（不提交）**

汇总验证证据（命令输出摘要），`git status` 列出全部改动文件清单向用户汇报，**等待用户明确同意后才可能执行提交**（由主控操作，非本计划步骤）。
