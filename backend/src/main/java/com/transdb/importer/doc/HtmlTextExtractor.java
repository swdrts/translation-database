package com.transdb.importer.doc;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML/XHTML 正文抽取：按文档顺序遍历 h1-h6 / p / li，标题行开新章，其余进段落。
 * EPUB 内嵌 XHTML 与独立 HTML 文件共用此逻辑。
 */
final class HtmlTextExtractor {

    private HtmlTextExtractor() {
    }

    static List<ParsedDocument.DocChapter> chaptersFrom(Document doc) {
        List<ParsedDocument.DocChapter> chapters = new ArrayList<>();
        String chapterTitle = null;
        List<String> paragraphs = new ArrayList<>();

        for (Element el : doc.select("h1,h2,h3,h4,h5,h6,p,li")) {
            // 目录 nav（EPUB3 toc）与 li 内嵌 p 跳过，避免重复
            if (el.closest("nav") != null) {
                continue;
            }
            if ("p".equals(el.tagName()) && el.closest("li") != null) {
                continue;
            }
            String text = el.text().strip();
            if (text.isEmpty() || !hasLetter(text)) {
                continue; // 空行与纯数字/符号行（电子书页码）
            }
            if (isHeading(el)) {
                if (chapterTitle != null && paragraphs.isEmpty()) {
                    // 层级标题合并：「卷一」+「学而第一」
                    chapterTitle = chapterTitle + " · " + text;
                } else {
                    if (!paragraphs.isEmpty()) {
                        chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
                        paragraphs = new ArrayList<>();
                    }
                    chapterTitle = text;
                }
            } else {
                paragraphs.add(text);
            }
        }
        if (!paragraphs.isEmpty() || chapterTitle != null) {
            chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
        }
        return chapters;
    }

    private static boolean isHeading(Element el) {
        String tag = el.tagName();
        return tag.length() == 2 && tag.charAt(0) == 'h'
                && tag.charAt(1) >= '1' && tag.charAt(1) <= '6';
    }

    private static boolean hasLetter(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }
}
