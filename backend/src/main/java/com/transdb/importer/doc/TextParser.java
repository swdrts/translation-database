package com.transdb.importer.doc;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本导入：.txt 走通用切分器；.md（Markdown）按 # 标题分章、空行分段，并剥离常见排版标记。
 * 编码识别：BOM 优先，其次严格 UTF-8，失败回退 GB18030（中文 txt 常见编码）。
 */
@Component
public class TextParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        String text;
        try {
            text = decode(content);
        } catch (Exception e) {
            throw BusinessException.of(ErrorCode.IMPORT_FILE_UNREADABLE, "文本文件解码失败");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        List<ParsedDocument.DocChapter> chapters = lower.endsWith(".md") || lower.endsWith(".markdown")
                ? markdownChapters(text)
                : TextSegmenter.segmentLines(
                        TextSegmenter.dropNoiseLines(text.lines().toList()), true);
        return new ParsedDocument(null, null, chapters);
    }

    // ---------- Markdown ----------

    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern MD_RULE = Pattern.compile("^\\s{0,3}([-*_])\\s*(?:\\1\\s*){2,}$");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*|__([^_]+)__");
    private static final Pattern MD_ITALIC = Pattern.compile("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])");
    private static final Pattern MD_CODE = Pattern.compile("`([^`]*)`");

    private List<ParsedDocument.DocChapter> markdownChapters(String text) {
        List<ParsedDocument.DocChapter> chapters = new ArrayList<>();
        String chapterTitle = null;
        List<String> paragraphs = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            Matcher heading = MD_HEADING.matcher(line);
            if (heading.matches()) {
                buffer = flush(buffer, paragraphs);
                String title = stripInline(heading.group(2));
                if (chapterTitle != null && paragraphs.isEmpty()) {
                    chapterTitle = chapterTitle + " · " + title;
                } else {
                    if (!paragraphs.isEmpty()) {
                        chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
                        paragraphs = new ArrayList<>();
                    }
                    chapterTitle = title;
                }
                continue;
            }
            if (line.isEmpty() || MD_RULE.matcher(line).matches()) {
                buffer = flush(buffer, paragraphs);
                continue;
            }
            // 列表项、引用、有序列表各自成段，不与上一行合并
            if (startsNewBlock(line)) {
                buffer = flush(buffer, paragraphs);
            }
            String cleaned = stripInline(line);
            if (!cleaned.isEmpty()) {
                buffer = buffer.isEmpty() ? new StringBuilder(cleaned)
                        : buffer.append(' ').append(cleaned);
            }
        }
        flush(buffer, paragraphs);
        if (!paragraphs.isEmpty() || chapterTitle != null) {
            chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
        }
        return chapters;
    }

    /** 列表项 / 引用 / 有序列表行：自成一段的开头。 */
    private static boolean startsNewBlock(String line) {
        return line.startsWith(">")
                || line.matches("^[-*+]\\s+.*")
                || line.matches("^\\d{1,3}[.)]\\s+.*");
    }

    /** 剥离引用符、列表符与行内加粗/斜体/代码/链接标记，只留纯文字。 */
    private static String stripInline(String line) {
        String s = line;
        if (s.startsWith(">")) {
            s = s.replaceFirst("^>\\s?", "");
        }
        s = s.replaceFirst("^\\s*[-*+]\\s+", "");
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = MD_BOLD.matcher(s).replaceAll("$1$2");
        s = MD_ITALIC.matcher(s).replaceAll("$1");
        s = MD_CODE.matcher(s).replaceAll("$1");
        return s.strip();
    }

    private static StringBuilder flush(StringBuilder buffer, List<String> paragraphs) {
        if (!buffer.isEmpty()) {
            paragraphs.add(buffer.toString());
        }
        return new StringBuilder();
    }

    // ---------- 编码识别 ----------

    static String decode(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strict.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }
}
