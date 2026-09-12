package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.ImportChapterStatVO;
import com.transdb.dto.ImportEditStatsVO;
import com.transdb.dto.ImportRowEditRequest;
import com.transdb.dto.ImportRowsPageVO;
import com.transdb.repository.SegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 编辑服务纯逻辑测试：mock SegmentRepository，真实 ImportPreviewStore。 */
class ImportPreviewEditServiceTest {

    private ImportPreviewStore store;
    private SegmentRepository repo;
    private ImportPreviewEditService service;

    @BeforeEach
    void setup() {
        store = new ImportPreviewStore(new ImportProperties(100000, 60));
        repo = mock(SegmentRepository.class);
        service = new ImportPreviewEditService(store, repo);
    }

    // ---- helpers ----

    private static Map<String, String> fields(String source, String chapter) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("source_text", source);
        f.put("translated_text", "");
        if (chapter != null) {
            f.put("chapter", chapter);
        }
        f.put("work_title", "论语");
        return f;
    }

    private static ImportRowPlan plan(long rowId, ImportRowPlan.PlanType type,
                                      String text, String chapter) {
        return plan(rowId, type, text, chapter, null);
    }

    private static ImportRowPlan plan(long rowId, ImportRowPlan.PlanType type,
                                      String text, String chapter, Long existingId) {
        return new ImportRowPlan(rowId, type, new ParsedRow((int) rowId, fields(text, chapter)),
                existingId, ContentHash.sha256(text, ""), false);
    }

    private ImportPreviewStore.ImportPreviewSession session(DuplicateStrategy strategy,
                                                            ImportRowPlan... plans) {
        List<ImportRowPlan> rows = new ArrayList<>(List.of(plans));
        String id = store.create(1L, strategy, rows, rows.size(), List.of(),
                ImportSourceType.DOCUMENT, SegmentStatus.DRAFT);
        return store.requireOwned(id, 1L, ImportSourceType.DOCUMENT);
    }

    /** 建一个全部 IMPORT 的 SOURCE 会话（SKIP 策略）。 */
    private ImportPreviewStore.ImportPreviewSession session(String... texts) {
        ImportRowPlan[] plans = new ImportRowPlan[texts.length];
        for (int i = 0; i < texts.length; i++) {
            plans[i] = plan(i + 1, ImportRowPlan.PlanType.IMPORT, texts[i], "学而第一");
        }
        return session(DuplicateStrategy.SKIP, plans);
    }

    /** 构造库内既有条目：真实实体 + 反射写入自增 id（单元测试无 JPA 生成器）。 */
    private static Segment dbSegment(long id, String source, String translated) {
        Segment s = new Segment();
        s.setSourceText(source);
        s.setTranslatedText(translated);
        s.setStatus(SegmentStatus.PUBLISHED);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    // ---- rows / chapters / stats ----

    @Test
    void rowsPaginatesFiltersAndMapsRowVO() {
        ImportRowPlan noChapter = plan(1, ImportRowPlan.PlanType.IMPORT, "第一章句子。", null);
        ImportRowPlan second = plan(2, ImportRowPlan.PlanType.SKIP, "第二章句子。", "为政第二");
        ImportRowPlan third = plan(3, ImportRowPlan.PlanType.OVERWRITE, "第三章句子。", "为政第二");
        var s = session(DuplicateStrategy.SKIP, noChapter, second, third);

        ImportRowsPageVO page = service.rows(s, null, false, 300, 10, 1, 2);
        assertThat(page.rows()).hasSize(1);
        ImportRowsPageVO.RowVO vo = page.rows().get(0);
        assertThat(vo.rowId()).isEqualTo(3);
        assertThat(vo.seq()).isEqualTo(3);
        assertThat(vo.prevRowId()).isEqualTo(2);
        assertThat(vo.chapter()).isEqualTo("为政第二");
        assertThat(vo.text()).isEqualTo("第三章句子。");
        assertThat(vo.planType()).isEqualTo("OVERWRITE");
        assertThat(vo.edited()).isFalse();
        assertThat(page.stats()).isEqualTo(new ImportEditStatsVO(3, 1, 1, 1));

        ImportRowsPageVO firstPage = service.rows(s, null, false, 300, 10, 0, 2);
        assertThat(firstPage.rows().get(0).chapter()).isEmpty();
        assertThat(firstPage.rows().get(0).prevRowId()).isEqualTo(-1);
    }

    @Test
    void rowsSuspiciousFilterKeepsOnlyTooLong() {
        String normal = "十二个字左右的普通句子！";                       // 12 字，介于阈值之间
        String longText = "长".repeat(500);
        String another = "另一个普通句子也十二字。";                       // 12 字
        var s = session(normal, longText, another);

        ImportRowsPageVO page = service.rows(s, null, true, 300, 10, 0, 100);
        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).text()).hasSize(500);
    }

    @Test
    void rowsFiltersByExactChapterIncludingUnassigned() {
        ImportRowPlan a = plan(1, ImportRowPlan.PlanType.IMPORT, "甲句。", "学而第一");
        ImportRowPlan b = plan(2, ImportRowPlan.PlanType.IMPORT, "乙句。", null);
        var s = session(DuplicateStrategy.SKIP, a, b);

        assertThat(service.rows(s, "学而第一", false, 300, 10, 0, 100).rows())
                .extracting(ImportRowsPageVO.RowVO::text).containsExactly("甲句。");
        assertThat(service.rows(s, "", false, 300, 10, 0, 100).rows())
                .extracting(ImportRowsPageVO.RowVO::text).containsExactly("乙句。");
    }

    @Test
    void rowsTotalReflectsFilterWhileStatsStaysSessionWide() {
        ImportRowPlan a = plan(1, ImportRowPlan.PlanType.IMPORT, "甲句。", "学而第一");
        ImportRowPlan b = plan(2, ImportRowPlan.PlanType.IMPORT, "乙句。", "学而第一");
        ImportRowPlan c = plan(3, ImportRowPlan.PlanType.IMPORT, "丙句。", "为政第二");
        var s = session(DuplicateStrategy.SKIP, a, b, c);

        ImportRowsPageVO unfiltered = service.rows(s, null, false, 300, 10, 0, 100);
        assertThat(unfiltered.total()).isEqualTo(3);
        assertThat(unfiltered.stats().totalRows()).isEqualTo(3);

        ImportRowsPageVO filtered = service.rows(s, "学而第一", false, 300, 10, 0, 100);
        assertThat(filtered.total()).isEqualTo(2);
        assertThat(filtered.totalPages()).isEqualTo(1);
        assertThat(filtered.stats().totalRows()).isEqualTo(3);
    }

    @Test
    void chaptersCountsByTitleWithUnassignedLast() {
        ImportRowPlan a = plan(1, ImportRowPlan.PlanType.IMPORT, "甲句。", "学而第一");
        ImportRowPlan b = plan(2, ImportRowPlan.PlanType.IMPORT, "乙句。", "学而第一");
        ImportRowPlan c = plan(3, ImportRowPlan.PlanType.IMPORT, "丙句。", "为政第二");
        ImportRowPlan d = plan(4, ImportRowPlan.PlanType.IMPORT, "丁句。", null);
        var s = session(DuplicateStrategy.SKIP, a, b, c, d);

        assertThat(service.chapters(s)).containsExactly(
                new ImportChapterStatVO("学而第一", 2),
                new ImportChapterStatVO("为政第二", 1),
                new ImportChapterStatVO("", 1));
    }

    // ---- editRow ----

    @Test
    void editRowChangesTextAndMarksEdited() {
        var s = session("学而时习之，不亦说乎？");
        ImportRowPlan updated = service.editRow(s, 1, new ImportRowEditRequest("改过的句子。", null));

        assertThat(updated.edited()).isTrue();
        assertThat(updated.rowId()).isEqualTo(1);
        assertThat(updated.type()).isEqualTo(ImportRowPlan.PlanType.IMPORT);
        assertThat(s.rows().get(0).row().get("source_text")).isEqualTo("改过的句子。");
        assertThat(service.rows(s, null, false, 300, 10, 0, 100).rows().get(0).text())
                .isEqualTo("改过的句子。");
    }

    @Test
    void editRowRejectsBlankText() {
        var s = session("原句。");
        assertThatThrownBy(() -> service.editRow(s, 1, new ImportRowEditRequest("   ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分段内容不能为空");
    }

    @Test
    void editRowRejectsWhenNothingChanged() {
        var s = session("原句。");
        assertThatThrownBy(() -> service.editRow(s, 1, new ImportRowEditRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少修改一项");
    }

    @Test
    void editRowChangesChapterOnly() {
        var s = session("原句。");
        String hashBefore = s.rows().get(0).contentHash();
        ImportRowPlan updated = service.editRow(s, 1, new ImportRowEditRequest(null, "为政第二"));

        assertThat(updated.edited()).isTrue();
        assertThat(updated.contentHash()).isEqualTo(hashBefore);
        assertThat(s.rows().get(0).row().get("chapter")).isEqualTo("为政第二");
    }

    @Test
    void editRowUnknownRowIdRejected() {
        var s = session("原句。");
        assertThatThrownBy(() -> service.editRow(s, 99, new ImportRowEditRequest("新句。", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ---- merge ----

    @Test
    void mergeTwoRowsKeepsFirstChapterAndJoinsText() {
        var s = session("学而时习之，", "不亦说乎？");
        ImportRowPlan merged = service.mergeRows(s, List.of(1L, 2L));

        assertThat(s.rows()).hasSize(1);
        assertThat(merged.rowId()).isEqualTo(3);   // 新分配 rowId（建会话 2 行 → 下一号 3）
        assertThat(merged.edited()).isTrue();
        assertThat(merged.row().get("source_text")).isEqualTo("学而时习之，不亦说乎？");
        assertThat(merged.row().get("chapter")).isEqualTo("学而第一");
    }

    @Test
    void mergeJoinsLatinWithSpace() {
        var s = session("Hello,", "world");
        ImportRowPlan merged = service.mergeRows(s, List.of(1L, 2L));
        assertThat(merged.row().get("source_text")).isEqualTo("Hello, world");
    }

    @Test
    void mergeRejectsSingleRow() {
        var s = session("甲。", "乙。");
        assertThatThrownBy(() -> service.mergeRows(s, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少选择两段");
    }

    @Test
    void mergeRejectsDiscontinuousRows() {
        var s = session("甲。", "乙。", "丙。");
        assertThatThrownBy(() -> service.mergeRows(s, List.of(1L, 3L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不连续");
    }

    @Test
    void mergeRowIdNotFound() {
        var s = session("甲。", "乙。");
        assertThatThrownBy(() -> service.mergeRows(s, List.of(1L, 99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ---- split ----

    @Test
    void splitAtCharProducesTwoRows() {
        var s = session("学而时习之不亦说乎");
        List<ImportRowPlan> parts = service.splitRow(s, 1, 5);

        assertThat(parts).hasSize(2);
        assertThat(s.rows()).hasSize(2);
        assertThat(parts.get(0).rowId()).isEqualTo(1);
        assertThat(parts.get(1).rowId()).isEqualTo(2);   // nextRowId：1 行会话 → 下一号 2
        assertThat(parts.get(0).row().get("source_text")).isEqualTo("学而时习之");
        assertThat(parts.get(1).row().get("source_text")).isEqualTo("不亦说乎");
        assertThat(parts.get(0).edited()).isTrue();
        assertThat(parts.get(1).edited()).isTrue();
        assertThat(parts.get(0).row().get("chapter")).isEqualTo("学而第一");
        assertThat(parts.get(1).row().get("chapter")).isEqualTo("学而第一");
    }

    @Test
    void splitRejectsBoundaryPositions() {
        var s = session("学而时习之");
        assertThatThrownBy(() -> service.splitRow(s, 1, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拆分位置");
        assertThatThrownBy(() -> service.splitRow(s, 1, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拆分位置");
    }

    @Test
    void splitRejectsBlankPart() {
        var s = session("  甲乙");   // atChar=1 → 上段 stripTrailing 后为空
        assertThatThrownBy(() -> service.splitRow(s, 1, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能有空段");
    }

    // ---- delete ----

    @Test
    void deleteRowRemovesAndReturnsStats() {
        var s = session("甲句。", "乙句。", "丙句。");
        ImportEditStatsVO stats = service.deleteRow(s, 2);

        assertThat(s.rows()).hasSize(2);
        assertThat(stats.totalRows()).isEqualTo(2);
        assertThat(stats.willImportRows()).isEqualTo(2);
    }

    // ---- renameChapter ----

    @Test
    void renameChapterUpdatesAllRowsInChapter() {
        var s = session("甲句。", "乙句。");
        service.renameChapter(s, "学而第一", "学而");

        assertThat(s.rows()).allSatisfy(p -> {
            assertThat(p.row().get("chapter")).isEqualTo("学而");
            assertThat(p.edited()).isTrue();
        });
        assertThat(service.chapters(s)).containsExactly(new ImportChapterStatVO("学而", 2));
    }

    @Test
    void renameChapterToExistingMergesChapters() {
        ImportRowPlan a = plan(1, ImportRowPlan.PlanType.IMPORT, "甲句。", "学而第一");
        ImportRowPlan b = plan(2, ImportRowPlan.PlanType.IMPORT, "乙句。", "学而第一");
        ImportRowPlan c = plan(3, ImportRowPlan.PlanType.IMPORT, "丙句。", "为政第二");
        var s = session(DuplicateStrategy.SKIP, a, b, c);

        service.renameChapter(s, "学而第一", "为政第二");
        assertThat(service.chapters(s)).containsExactly(new ImportChapterStatVO("为政第二", 3));
    }

    @Test
    void renameChapterMissingFromRejected() {
        var s = session("甲句。");
        assertThatThrownBy(() -> service.renameChapter(s, "不存在的章", "新章"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("章节不存在");
    }

    @Test
    void renameChapterToBlankUnassigns() {
        var s = session("甲句。");
        service.renameChapter(s, "学而第一", "");
        assertThat(service.chapters(s)).containsExactly(new ImportChapterStatVO("", 1));
    }

    // ---- 去重重评 ----

    @Test
    void editTextReevaluatesToImportWhenDuplicateGone() {
        String shared = "重复句子。";
        ImportRowPlan keeper = plan(1, ImportRowPlan.PlanType.IMPORT, shared, "学而第一");
        ImportRowPlan dup = plan(2, ImportRowPlan.PlanType.SKIP, shared, "学而第一", null);
        var s = session(DuplicateStrategy.SKIP, keeper, dup);

        ImportRowPlan updated = service.editRow(s, 2, new ImportRowEditRequest("改过的独立句子。", null));
        assertThat(updated.type()).isEqualTo(ImportRowPlan.PlanType.IMPORT);
        assertThat(service.statsOf(s).willImportRows()).isEqualTo(2);
    }

    @Test
    void editTextReevaluatesToSkipWhenMatchesDb() {
        var s = session("原句。");
        String dbText = "库内已有的句子。";
        String hash = ContentHash.sha256(dbText, "");
        Segment seg = dbSegment(42L, dbText, "");
        when(repo.findByContentHashIn(List.of(hash))).thenReturn(List.of(seg));

        ImportRowPlan updated = service.editRow(s, 1, new ImportRowEditRequest(dbText, null));
        assertThat(updated.type()).isEqualTo(ImportRowPlan.PlanType.SKIP);
        assertThat(updated.existingSegmentId()).isEqualTo(42L);
    }

    @Test
    void editTextProtectedWhenDbHasOtherSide() {
        var s = session("原句。");
        String dbText = "已配好译文的句子。";
        Segment seg = dbSegment(7L, dbText, "Already translated.");
        when(repo.findBySourceTextIn(List.of(dbText))).thenReturn(List.of(seg));

        ImportRowPlan updated = service.editRow(s, 1, new ImportRowEditRequest(dbText, null));
        assertThat(updated.type()).isEqualTo(ImportRowPlan.PlanType.SKIP);
        assertThat(updated.existingSegmentId()).isEqualTo(7L);
    }

    @Test
    void editTextStrategyOverwriteMakesOverwrite() {
        var s = session(DuplicateStrategy.OVERWRITE,
                plan(1, ImportRowPlan.PlanType.IMPORT, "原句。", "学而第一"));
        String dbText = "库内已有的句子。";
        String hash = ContentHash.sha256(dbText, "");
        Segment seg = dbSegment(42L, dbText, "");
        when(repo.findByContentHashIn(List.of(hash))).thenReturn(List.of(seg));

        ImportRowPlan updated = service.editRow(s, 1, new ImportRowEditRequest(dbText, null));
        assertThat(updated.type()).isEqualTo(ImportRowPlan.PlanType.OVERWRITE);
        assertThat(updated.existingSegmentId()).isEqualTo(42L);
    }

    @Test
    void deleteFirstOccurrenceRevivesFileDuplicates() {
        String shared = "重复句子。";
        ImportRowPlan keeper = plan(1, ImportRowPlan.PlanType.IMPORT, shared, "学而第一");
        ImportRowPlan dup = plan(2, ImportRowPlan.PlanType.SKIP, shared, "学而第一", null);
        var s = session(DuplicateStrategy.SKIP, keeper, dup);

        service.deleteRow(s, 1);
        assertThat(s.rows()).hasSize(1);
        assertThat(s.rows().get(0).type()).isEqualTo(ImportRowPlan.PlanType.IMPORT);
    }

    @Test
    void mergeThenHashRerevaluationMarksSkipOnInFileDup() {
        ImportRowPlan joined = plan(1, ImportRowPlan.PlanType.IMPORT, "甲乙", "学而第一");
        ImportRowPlan left = plan(2, ImportRowPlan.PlanType.IMPORT, "甲", "学而第一");
        ImportRowPlan right = plan(3, ImportRowPlan.PlanType.IMPORT, "乙", "学而第一");
        var s = session(DuplicateStrategy.SKIP, joined, left, right);

        ImportRowPlan merged = service.mergeRows(s, List.of(2L, 3L));
        assertThat(merged.row().get("source_text")).isEqualTo("甲乙");
        assertThat(merged.type()).isEqualTo(ImportRowPlan.PlanType.SKIP);
        assertThat(merged.existingSegmentId()).isNull();
        assertThat(s.rows().get(0).type()).isEqualTo(ImportRowPlan.PlanType.IMPORT);
    }

    @Test
    void editLaterRowToDuplicateEarlierRowSkipsItself() {
        var s = session("甲句。", "乙句。");
        // 把第 2 段改成与第 1 段同文：前面的保留，自己变文件内重复
        ImportRowPlan updated = service.editRow(s, 2, new ImportRowEditRequest("甲句。", null));

        assertThat(updated.type()).isEqualTo(ImportRowPlan.PlanType.SKIP);
        assertThat(updated.existingSegmentId()).isNull();
        assertThat(s.rows().get(0).type()).isEqualTo(ImportRowPlan.PlanType.IMPORT);
    }

    @Test
    void splitProducesNoFileDupWhenPartsDiffer() {
        var s = session("学而时习之，不亦说乎？");
        List<ImportRowPlan> parts = service.splitRow(s, 1, 5);
        assertThat(parts).allSatisfy(p -> assertThat(p.type()).isEqualTo(ImportRowPlan.PlanType.IMPORT));
    }

    @Test
    void editEarlierRowToDuplicateLaterRowDemotesLater() {
        var s = session("甲句。", "乙句。");
        // 把第 1 段改成与第 2 段同文：首个（第 1 段）是保留者，后续（第 2 段）降为文件内重复
        ImportRowPlan updated = service.editRow(s, 1, new ImportRowEditRequest("乙句。", null));

        assertThat(updated.type()).isEqualTo(ImportRowPlan.PlanType.IMPORT);
        assertThat(s.rows().get(1).type()).isEqualTo(ImportRowPlan.PlanType.SKIP);
        assertThat(s.rows().get(1).existingSegmentId()).isNull();
        assertThat(service.statsOf(s).willImportRows()).isEqualTo(1);
        assertThat(service.statsOf(s).skippedRows()).isEqualTo(1);
    }
}
