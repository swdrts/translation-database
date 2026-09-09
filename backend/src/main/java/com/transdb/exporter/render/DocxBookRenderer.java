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
