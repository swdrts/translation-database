package com.transdb.importer.doc;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** 单文件 HTML/HTM/XHTML 导入：复用 EPUB 正文抽取逻辑。 */
@Component
public class HtmlParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml");
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(content), null, "");
        } catch (IOException e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "HTML 解析失败");
        }
        String title = doc.title() == null || doc.title().isBlank() ? null : doc.title().strip();
        String author = doc.selectFirst("meta[name=author]") == null
                ? null : doc.selectFirst("meta[name=author]").attr("content").strip();
        List<ParsedDocument.DocChapter> chapters = HtmlTextExtractor.chaptersFrom(doc);
        return new ParsedDocument(
                title == null || title.isEmpty() ? null : title,
                author == null || author.isEmpty() ? null : author,
                chapters);
    }
}
