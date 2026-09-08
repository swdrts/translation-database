package com.transdb.importer;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.dto.ImportPreviewVO;
import com.transdb.importer.doc.DocumentParser;
import com.transdb.importer.doc.ParsedDocument;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整本书/文档导入：把 EPUB、PDF、Word、TXT 等格式解析成章节段落，
 * 每个段落成为一条「仅原文」的行（译文留空待补），再走统一的预览/确认管线。
 */
@Service
@RequiredArgsConstructor
public class DocumentImportService {

    /** 完全相同的短段落出现达到该次数即视为页眉/页脚剔除（PDF 常见）。 */
    static final int REPEAT_HEADER_THRESHOLD = 5;
    private static final int REPEAT_HEADER_MAX_LEN = 50;

    private final List<DocumentParser> parsers;
    private final ImportPreviewService previewService;

    public ImportPreviewVO buildPreview(String filename, InputStream in,
                                        DuplicateStrategy strategy, LoginUser operator) {
        DocumentParser parser = parsers.stream().filter(p -> p.supports(filename)).findFirst()
                .orElseThrow(() -> BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE,
                        "暂不支持的文件类型: " + filename
                                + "（支持 EPUB / PDF / Word(docx,doc) / TXT / Markdown / HTML）"));
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "文件读取失败");
        }
        ParsedDocument doc = parser.parse(filename, bytes);
        List<ParsedRow> rows = toRows(doc);
        if (rows.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS,
                    "没有从文件中识别出可导入的文字内容（可能是扫描版 PDF，需要文字版才能导入）");
        }
        return previewService.buildDocumentPreview(rows, strategy, operator,
                doc.title(), doc.author(), chapterCount(doc));
    }

    /** 段落 → 行：过滤页码/纯符号行与页眉页脚，套上识别到的书名/作者/章节。 */
    private List<ParsedRow> toRows(ParsedDocument doc) {
        Map<String, Integer> frequency = new HashMap<>();
        for (ParsedDocument.DocChapter ch : doc.chapters()) {
            for (String para : ch.paragraphs()) {
                String text = para.strip();
                if (!text.isEmpty()) {
                    frequency.merge(text, 1, Integer::sum);
                }
            }
        }

        List<ParsedRow> rows = new ArrayList<>();
        int line = 0;
        for (ParsedDocument.DocChapter ch : doc.chapters()) {
            for (String para : ch.paragraphs()) {
                String text = para.strip();
                if (!hasLetter(text)) {
                    continue; // 页码、分隔符等无文字内容
                }
                Integer count = frequency.get(text);
                if (count != null && count >= REPEAT_HEADER_THRESHOLD
                        && text.length() <= REPEAT_HEADER_MAX_LEN) {
                    continue; // 页眉/页脚：多页重复出现的同一短行
                }
                line++;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("source_text", text);
                fields.put("translated_text", "");
                fields.put("work_title", doc.title());
                fields.put("chapter", ch.title());
                fields.put("author", doc.author());
                rows.add(new ParsedRow(line, fields));
            }
        }
        return rows;
    }

    private static int chapterCount(ParsedDocument doc) {
        return (int) doc.chapters().stream()
                .filter(ch -> ch.title() != null && !ch.paragraphs().isEmpty())
                .count();
    }

    private static boolean hasLetter(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }
}
