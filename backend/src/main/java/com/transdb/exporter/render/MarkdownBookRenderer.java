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
