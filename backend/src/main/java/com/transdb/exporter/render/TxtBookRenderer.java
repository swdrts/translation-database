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
        String byline = byline(book);
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

    private String byline(BookDocument book) {
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
}
