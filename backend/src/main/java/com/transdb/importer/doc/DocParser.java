package com.transdb.importer.doc;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 老版 Word(.doc) 解析（POI HWPF）。段落由 Word 自身结构给出，只需识别章节标题行。 */
@Component
public class DocParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".doc");
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content));
             WordExtractor extractor = new WordExtractor(doc)) {
            String title = null;
            String author = null;
            try {
                var si = doc.getSummaryInformation();
                if (si != null) {
                    title = trimToNull(si.getTitle());
                    author = trimToNull(si.getAuthor());
                }
            } catch (Exception ignored) {
                // 摘要信息缺失不影响正文抽取
            }
            List<String> lines = Arrays.stream(extractor.getParagraphText())
                    .map(String::strip)
                    .toList();
            return new ParsedDocument(title, author, TextSegmenter.segmentLines(lines, false));
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                    "Word(.doc) 解析失败: " + rootMessage(e));
        }
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
