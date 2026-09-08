package com.transdb.importer.doc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 纯文本行 → 章节与段落的通用切分器，供 PDF / TXT / DOC 等无结构标记的文本使用。
 *
 * 启发式规则（面向中文典籍与常见书籍版式）：
 * - 章节标题行：形如「第X章/节/篇/卷/回」「学而第一」「Chapter N」「序言/楔子/后记」的短行；
 * - 硬换行合并：joinWrapped=true（PDF 等重排版文本）时，上一行未以句末标点结尾则与下一行
 *   合并为同一段（处理每行 N 字的排版换行）；以句末标点收尾的行独立成段；
 * - joinWrapped=false（Word 等天然按段落分行的来源）时每行即一段，不做合并；
 * - 空行与换页符结束当前段落。
 */
final class TextSegmenter {

    private TextSegmenter() {
    }

    private static final int MAX_TITLE_LEN = 40;

    /** 句末标点：行尾出现这些符号时视为一段结束（硬换行合并场景）。 */
    private static final String TERMINAL_PUNCT = "。！？；…!?;”』」》）)";

    private static final Pattern CHAPTER_TITLE = Pattern.compile(
            "^\\s*(?:"
                    + "第[〇零一二三四五六七八九十百千万0-9]{1,12}[章节篇卷回部集]"
                    + "|[\\u4e00-\\u9fa5]{1,10}第[〇零一二三四五六七八九十]{1,4}"
                    + "|Chapter\\s+[0-9IVXivx]{1,7}.{0,20}"
                    + "|序(?:言)?|自序|前言|引子|楔子|后记|结语"
                    + "|附录\\S{0,10}"
                    + ")\\s*$");

    static boolean isChapterTitle(String line) {
        String s = line.strip();
        if (s.isEmpty() || s.length() > MAX_TITLE_LEN) {
            return false;
        }
        char last = s.charAt(s.length() - 1);
        if (TERMINAL_PUNCT.indexOf(last) >= 0 || last == '，' || last == ',') {
            return false;
        }
        return CHAPTER_TITLE.matcher(s).matches();
    }

    /**
     * 行级噪声剔除（段落切分前）：去掉无文字的行（页码、分隔符），以及完全相同的
     * 短行重复出现达阈值次（页眉/页脚）——必须在合并段落之前做，否则页眉会被并入正文段。
     */
    static List<String> dropNoiseLines(List<String> lines) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String line : lines) {
            String s = line.strip();
            if (!s.isEmpty()) {
                frequency.merge(s, 1, Integer::sum);
            }
        }
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty()) {
                result.add(s);
                continue;
            }
            if (!hasLetter(s)) {
                continue;
            }
            Integer count = frequency.get(s);
            if (count != null && count >= REPEATED_LINE_THRESHOLD && s.length() <= REPEATED_LINE_MAX_LEN) {
                continue;
            }
            result.add(s);
        }
        return result;
    }

    private static final int REPEATED_LINE_THRESHOLD = 5;
    private static final int REPEATED_LINE_MAX_LEN = 50;

    private static boolean hasLetter(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }

    static List<ParsedDocument.DocChapter> segmentLines(List<String> lines, boolean joinWrapped) {
        List<ParsedDocument.DocChapter> chapters = new ArrayList<>();
        String chapterTitle = null;
        List<String> paragraphs = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean bufferedEndsTerminal = true;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                buffer = flush(buffer, paragraphs);
                continue;
            }
            if (isChapterTitle(line)) {
                buffer = flush(buffer, paragraphs);
                // 上一章无内容时合并层级标题（如「卷一」+「学而第一」）
                if (chapterTitle != null && paragraphs.isEmpty()) {
                    chapterTitle = chapterTitle + " · " + line;
                } else {
                    if (!paragraphs.isEmpty()) {
                        chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
                        paragraphs = new ArrayList<>();
                    }
                    chapterTitle = line;
                }
                continue;
            }
            if (joinWrapped) {
                if (buffer.isEmpty()) {
                    buffer.append(line);
                } else if (bufferedEndsTerminal) {
                    paragraphs.add(buffer.toString());
                    buffer.setLength(0);
                    buffer.append(line);
                } else {
                    buffer.append(joinSeparator(buffer, line)).append(line);
                }
                bufferedEndsTerminal = isTerminal(buffer.charAt(buffer.length() - 1));
            } else {
                paragraphs.add(line);
            }
        }
        flush(buffer, paragraphs);
        if (!paragraphs.isEmpty() || chapterTitle != null) {
            chapters.add(new ParsedDocument.DocChapter(chapterTitle, paragraphs));
        }
        return chapters;
    }

    private static StringBuilder flush(StringBuilder buffer, List<String> paragraphs) {
        if (!buffer.isEmpty()) {
            paragraphs.add(buffer.toString());
        }
        return new StringBuilder();
    }

    private static boolean isTerminal(char c) {
        return TERMINAL_PUNCT.indexOf(c) >= 0;
    }

    /** CJK 字符之间直接拼接，拉丁字母/数字之间补空格。 */
    private static char[] joinSeparator(StringBuilder left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return new char[0];
        }
        char a = left.charAt(left.length() - 1);
        char b = right.charAt(0);
        boolean aLatin = a < 0x80 && (Character.isLetterOrDigit(a) || a == ',');
        boolean bLatin = b < 0x80 && Character.isLetterOrDigit(b);
        return aLatin && bLatin ? new char[]{' '} : new char[0];
    }
}
