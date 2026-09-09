package com.transdb.exporter.render;

import com.transdb.exporter.BookDocument;
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
