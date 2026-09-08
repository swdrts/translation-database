package com.transdb.importer.doc;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Word(.docx) 解析：标题样式（Heading/标题 N）开新章，正文样式进段落；书名/作者取文档属性。 */
@Component
public class DocxParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            String title = null;
            String author = null;
            try {
                var core = doc.getProperties().getCoreProperties();
                title = trimToNull(core.getTitle());
                author = trimToNull(core.getCreator());
            } catch (Exception ignored) {
                // 文档属性缺失不影响正文抽取
            }

            List<ParsedDocument.DocChapter> chapters = new ArrayList<>();
            String chapterTitle = null;
            List<String> paragraphs = new ArrayList<>();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText() == null ? "" : p.getText().strip();
                if (isHeading(p)) {
                    if (text.isEmpty()) {
                        continue;
                    }
                    if (chapterTitle != null && paragraphs.isEmpty()) {
                        chapterTitle = chapterTitle + " · " + text;
                    } else {
                        if (!paragraphs.isEmpty()) {
                            chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
                            paragraphs = new ArrayList<>();
                        }
                        chapterTitle = text;
                    }
                } else if (!text.isEmpty()) {
                    paragraphs.add(text);
                }
            }
            if (!paragraphs.isEmpty() || chapterTitle != null) {
                chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
            }
            return new ParsedDocument(title, author, chapters);
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                    "Word(.docx) 解析失败: " + rootMessage(e));
        }
    }

    /** 样式 id 通常为 Heading1..6（与界面语言无关）；样式名本地化时再兜底匹配「标题」。 */
    private static boolean isHeading(XWPFParagraph p) {
        String styleId = p.getStyleID();
        if (styleId == null) {
            return false;
        }
        if (styleId.toLowerCase(Locale.ROOT).contains("heading")) {
            return true;
        }
        try {
            XWPFStyle style = p.getDocument().getStyles().getStyle(styleId);
            if (style != null && style.getName() != null) {
                String name = style.getName();
                return name.toLowerCase(Locale.ROOT).contains("heading") || name.contains("标题");
            }
        } catch (Exception ignored) {
            // styles.xml 缺失时按 id 判断即可
        }
        return false;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
