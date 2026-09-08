package com.transdb.importer.doc;

import com.transdb.common.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 各文档解析器的纯单元测试：用内存构造的最小样本文件验证解析行为。 */
class DocumentParsersTest {

    // ---------- EPUB ----------

    @Test
    void epubParsesMetadataChaptersAndParagraphs() {
        byte[] epub = buildEpub();
        ParsedDocument doc = new EpubParser().parse("book.epub", epub);

        assertThat(doc.title()).isEqualTo("论语");
        assertThat(doc.author()).isEqualTo("孔门弟子");
        assertThat(doc.chapters()).hasSize(2);
        assertThat(doc.chapters().get(0).title()).isEqualTo("学而第一");
        assertThat(doc.chapters().get(0).paragraphs())
                .containsExactly("子曰：学而时习之，不亦说乎？", "有朋自远方来，不亦乐乎？");
        assertThat(doc.chapters().get(1).title()).isEqualTo("为政第二");
        assertThat(doc.chapters().get(1).paragraphs()).containsExactly("为政以德，譬如北辰。");
    }

    @Test
    void epubWithoutContainerXmlIsUnreadable() {
        byte[] notEpub = zipOf(java.util.Map.of("hello.txt", "hi".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> new EpubParser().parse("bad.epub", notEpub))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("EPUB");
    }

    private static byte[] buildEpub() {
        String ch1 = """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>c1</title></head><body>
                <h1>学而第一</h1>
                <p>子曰：学而时习之，不亦说乎？</p>
                <p>有朋自远方来，不亦乐乎？</p>
                </body></html>
                """;
        String ch2 = """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>c2</title></head><body>
                <h1>为政第二</h1>
                <p>为政以德，譬如北辰。</p>
                </body></html>
                """;
        String opf = """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>论语</dc:title>
                    <dc:creator>孔门弟子</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
                """;
        String container = """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf"
                    media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """;
        return zipOf(java.util.Map.of(
                "mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8),
                "META-INF/container.xml", container.getBytes(StandardCharsets.UTF_8),
                "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8),
                "OEBPS/text/ch1.xhtml", ch1.getBytes(StandardCharsets.UTF_8),
                "OEBPS/text/ch2.xhtml", ch2.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] zipOf(java.util.Map<String, byte[]> entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- DOCX ----------

    @Test
    void docxParsesHeadingsAndCoreProperties() throws IOException {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.getProperties().getCoreProperties().setTitle("孟子");
            doc.getProperties().getCoreProperties().setCreator("孟轲");
            heading(doc, "梁惠王上");
            body(doc, "孟子见梁惠王。");
            body(doc, "王曰：叟不远千里而来。");
            heading(doc, "公孙丑上");
            body(doc, "夫子当路于齐。");
            doc.write(out);
            bytes = out.toByteArray();
        }

        ParsedDocument parsed = new DocxParser().parse("mengzi.docx", bytes);
        assertThat(parsed.title()).isEqualTo("孟子");
        assertThat(parsed.author()).isEqualTo("孟轲");
        assertThat(parsed.chapters()).hasSize(2);
        assertThat(parsed.chapters().get(0).title()).isEqualTo("梁惠王上");
        assertThat(parsed.chapters().get(0).paragraphs()).hasSize(2);
        assertThat(parsed.chapters().get(1).paragraphs()).containsExactly("夫子当路于齐。");
    }

    private static void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.getCTP().addNewPPr().addNewPStyle().setVal("Heading1");
        p.createRun().setText(text);
    }

    private static void body(XWPFDocument doc, String text) {
        doc.createParagraph().createRun().setText(text);
    }

    @Test
    void docxGarbageBytesIsUnreadable() {
        assertThatThrownBy(() -> new DocxParser().parse("x.docx", "junk".getBytes()))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- PDF ----------

    @Test
    void pdfExtractsParagraphs() throws IOException {
        byte[] bytes;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Is it not a pleasure to learn and practise what one has learnt?");
                cs.endText();
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Is it not delightful to have friends coming from distant quarters?");
                cs.endText();
            }
            doc.save(out);
            bytes = out.toByteArray();
        }

        ParsedDocument parsed = new PdfParser().parse("lunyu.pdf", bytes);
        assertThat(parsed.chapters()).hasSize(1);
        List<String> paragraphs = parsed.chapters().get(0).paragraphs();
        // 两行均以句末标点收尾 → 各自成段
        assertThat(paragraphs).hasSize(2);
        assertThat(paragraphs.get(0))
                .contains("Is it not a pleasure to learn and practise what one has learnt?");
    }

    @Test
    void pdfGarbageBytesIsUnreadable() {
        assertThatThrownBy(() -> new PdfParser().parse("x.pdf", "junk".getBytes()))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- TXT / Markdown ----------

    @Test
    void txtDecodesGb18030AndSplitsParagraphs() {
        String text = "学而时习之，不亦说乎？\n\n有朋自远方来，不亦乐乎？";
        byte[] gbBytes = text.getBytes(Charset.forName("GB18030"));
        ParsedDocument doc = new TextParser().parse("lunyu.txt", gbBytes);
        assertThat(doc.chapters()).hasSize(1);
        assertThat(doc.chapters().get(0).paragraphs()).containsExactly(
                "学而时习之，不亦说乎？", "有朋自远方来，不亦乐乎？");
    }

    @Test
    void txtJoinsHardWrappedLines() {
        String text = "子曰：学而时习之，\n不亦说乎？\n\n人不知而不愠，\n不亦君子乎？";
        ParsedDocument doc = new TextParser().parse("a.txt",
                text.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.chapters().get(0).paragraphs()).containsExactly(
                "子曰：学而时习之，不亦说乎？", "人不知而不愠，不亦君子乎？");
    }

    @Test
    void txtRecognizesChapterTitles() {
        String text = "学而第一\n子曰：学而时习之，不亦说乎？\n\n为政第二\n为政以德，譬如北辰。";
        ParsedDocument doc = new TextParser().parse("a.txt",
                text.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.chapters()).hasSize(2);
        assertThat(doc.chapters().get(0).title()).isEqualTo("学而第一");
        assertThat(doc.chapters().get(1).title()).isEqualTo("为政第二");
    }

    @Test
    void txtUtf8BomIsStripped() {
        byte[] bytes = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "温故而知新，可以为师矣。".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bytes.length + body.length];
        System.arraycopy(bytes, 0, all, 0, bytes.length);
        System.arraycopy(body, 0, all, bytes.length, body.length);
        ParsedDocument doc = new TextParser().parse("bom.txt", all);
        assertThat(doc.chapters().get(0).paragraphs())
                .containsExactly("温故而知新，可以为师矣。");
    }

    @Test
    void markdownHeadingsAndInlineMarkers() {
        String md = """
                # 论语

                ## 学而第一

                - 学而时习之，**不亦说乎**？
                - 有朋自远方来，`不亦乐乎`？

                ## 为政第二

                > 为政以德，譬如北辰。
                """;
        ParsedDocument doc = new TextParser().parse("lunyu.md",
                md.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.chapters()).hasSize(2);
        assertThat(doc.chapters().get(0).title()).isEqualTo("论语 · 学而第一");
        assertThat(doc.chapters().get(0).paragraphs()).containsExactly(
                "学而时习之，不亦说乎？", "有朋自远方来，不亦乐乎？");
        assertThat(doc.chapters().get(1).paragraphs()).containsExactly("为政以德，譬如北辰。");
    }

    // ---------- HTML ----------

    @Test
    void htmlParsesTitleAuthorAndChapters() {
        String html = """
                <html><head><title>孟子</title>
                <meta name="author" content="孟轲"></head>
                <body><h1>梁惠王上</h1><p>孟子见梁惠王。</p><p>3</p></body></html>
                """;
        ParsedDocument doc = new HtmlParser().parse("mengzi.html",
                html.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.title()).isEqualTo("孟子");
        assertThat(doc.author()).isEqualTo("孟轲");
        assertThat(doc.chapters()).hasSize(1);
        assertThat(doc.chapters().get(0).paragraphs()).containsExactly("孟子见梁惠王。");
    }

    // ---------- 老版 .doc ----------

    @Test
    void docParserSupportsAndRejectsGarbage() {
        assertThat(new DocParser().supports("a.doc")).isTrue();
        assertThat(new DocParser().supports("a.docx")).isFalse();
        assertThatThrownBy(() -> new DocParser().parse("x.doc", "junk".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Word(.doc)");
    }

    @Test
    void supportsByExtension() {
        assertThat(new EpubParser().supports("b.epub")).isTrue();
        assertThat(new PdfParser().supports("b.PDF")).isTrue();
        assertThat(new DocxParser().supports("b.docx")).isTrue();
        assertThat(new TextParser().supports("b.txt")).isTrue();
        assertThat(new TextParser().supports("b.md")).isTrue();
        assertThat(new HtmlParser().supports("b.html")).isTrue();
        assertThat(new HtmlParser().supports("b.htm")).isTrue();
        assertThat(new EpubParser().supports("b.pdf")).isFalse();
        assertThat(new TextParser().supports(null)).isFalse();
    }
}
