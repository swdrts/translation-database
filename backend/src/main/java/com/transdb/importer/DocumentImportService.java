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
                                        DuplicateStrategy strategy, ImportTextRole textRole,
                                        LoginUser operator) {
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
        List<ParsedRow> rows = toRows(doc, textRole);
        if (rows.isEmpty()) {
            throw BusinessException.of(ErrorCode.IMPORT_NO_ROWS,
                    "没有从文件中识别出可导入的文字内容（可能是扫描版 PDF，需要文字版才能导入）");
        }
        return previewService.buildDocumentPreview(rows, strategy, operator,
                doc.title(), doc.author(), chapterCount(doc), textRole);
    }

    /**
     * 段落 → 行：过滤页码/纯符号行与页眉页脚，套上识别到的书名/作者/章节。
     * 原文侧导入：source=段落、translated 留空；译文侧导入反之。
     */
    private List<ParsedRow> toRows(ParsedDocument doc, ImportTextRole textRole) {
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
                // 去除汉字之间的排版空格（EPUB/PDF 逐字定位、OCR 逐字输出常见），
                // 否则 ik 分词切不出连续词，搜索与高亮都无法命中
                String text = TextNormalizer.normalizeCjkSpaces(para.strip());
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
                if (textRole == ImportTextRole.TRANSLATION) {
                    fields.put("source_text", "");
                    fields.put("translated_text", text);
                } else {
                    fields.put("source_text", text);
                    fields.put("translated_text", "");
                }
                fields.put("work_title", doc.title());
                fields.put("chapter", normalizeChapterTitle(ch.title()));
                fields.put("author", doc.author());
                rows.add(new ParsedRow(line, fields));
            }
        }
        return rows;
    }

    /** 章节标题同样来自源文件，逐字空格一并归一化；无标题章节保持 null。 */
    private static String normalizeChapterTitle(String title) {
        return title == null ? null : TextNormalizer.normalizeCjkSpaces(title);
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
