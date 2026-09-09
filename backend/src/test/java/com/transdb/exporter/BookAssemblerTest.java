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
        assertThat(book.document().title()).isEqualTo("论语");
        assertThat(book.document().chapters()).hasSize(1);
        assertThat(book.document().chapters().get(0).units())
                .extracting(BookDocument.BookUnit::source, BookDocument.BookUnit::translated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("学而时习之", "To learn..."),
                        org.assertj.core.groups.Tuple.tuple("有朋自远方来", "Is it not delightful..."));
        assertThat(book.stats().segments()).isEqualTo(2);
        assertThat(book.stats().units()).isEqualTo(2);
        assertThat(book.stats().pairedUnits()).isEqualTo(2);
        assertThat(book.stats().chapters().get(0).warnings()).isEmpty();
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
        List<BookDocument.BookUnit> units = book.document().chapters().get(0).units();
        // 合并发生在原文段位置（原文批 id 更小、即书本顺序），成书 3 个单元
        assertThat(units).hasSize(3);
        assertThat(units).extracting(BookDocument.BookUnit::source, BookDocument.BookUnit::translated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("甲", "译甲"),
                        org.assertj.core.groups.Tuple.tuple("乙", "译乙"),
                        org.assertj.core.groups.Tuple.tuple("丙", "译丙"));
        BookStats.ChapterStat st = book.stats().chapters().get(0);
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
        List<BookDocument.BookUnit> units = book.document().chapters().get(0).units();
        assertThat(units).extracting(BookDocument.BookUnit::source, BookDocument.BookUnit::translated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("甲", "译甲"),
                        org.assertj.core.groups.Tuple.tuple("乙", null),
                        org.assertj.core.groups.Tuple.tuple("丙", null));
        assertThat(units.get(1).translatedSafe()).isEqualTo("〔未译〕");
        assertThat(units.get(1).sourceSafe()).isEqualTo("乙");
        List<String> warnings = book.stats().chapters().get(0).warnings();
        assertThat(warnings).anyMatch(w -> w.contains("3") && w.contains("1"));
    }

    @Test
    void leftoverTranslationKeepsTextWithMissingSourcePlaceholder() {
        AssembledBook book = assembler.assemble(List.of(
                seg("", "只有译文", "学而第一")));
        List<BookDocument.BookUnit> units = book.document().chapters().get(0).units();
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
        assertThat(book.stats().chapters().get(0).warnings())
                .anyMatch(w -> w.contains("人工核对"));
    }

    @Test
    void chaptersOrderByFirstAppearanceAndNullChapterGroups() {
        AssembledBook book = assembler.assemble(List.of(
                seg("一", "", "第一章"),
                seg("二", "", null),
                seg("三", "", "第二章"),
                seg("四", "", "第一章")));
        assertThat(book.document().chapters()).extracting(BookDocument.BookChapter::title)
                .containsExactly("第一章", null, "第二章");
        assertThat(book.document().chapters().get(0).units()).hasSize(2);
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
        assertThat(book.document().author()).isEqualTo("孔子弟子");
        assertThat(book.document().translator()).isEqualTo("James Legge");
    }

    @Test
    void blankTextTreatedAsMissing() {
        AssembledBook book = assembler.assemble(List.of(seg("甲", "  ", "学而第一")));
        List<BookDocument.BookUnit> units = book.document().chapters().get(0).units();
        assertThat(units.get(0).translated()).isNull();
        assertThat(units.get(0).translatedSafe()).isEqualTo("〔未译〕");
    }

    @Test
    void emptyBookYieldsEmptyDocument() {
        AssembledBook book = assembler.assemble(new ArrayList<>());
        assertThat(book.document().chapters()).isEmpty();
        assertThat(book.stats().units()).isZero();
    }
}
