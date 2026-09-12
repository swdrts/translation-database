package com.transdb.importer;

/**
 * 文本归一化工具：去除 CJK 字符之间的空白字符。
 *
 * 场景：EPUB/PDF 等来源的中文文本常在每字之间带空格（排版引擎逐字定位、
 * OCR 逐字输出所致），导致 ik 分词切不出连续词（如「第一回」），搜索无法命中、
 * 高亮无从谈起。拉丁字母/数字之间的空格是词边界，必须保留。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * 去掉两个 CJK 字符之间的空白；CJK 与拉丁字母/数字相邻处的空格保留。
     * null 安全；纯空白输入原样返回。
     */
    public static String normalizeCjkSpaces(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // 空白统一暂挂，待看到下一个有效字符后按前后字符类型决定保留或丢弃
        StringBuilder sb = new StringBuilder(text.length());
        Character prev = null;   // 上一个已输出的有效字符
        boolean pending = false; // 是否有待定空白
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (prev != null) {
                    pending = true;
                }
                continue;
            }
            if (pending) {
                boolean prevCjk = isCjk(prev);
                boolean cjk = isCjk(c);
                // CJK 之间的空白是排版噪声；其余（拉丁词间、CJK 与数字间）保留一个空格
                if (!(prevCjk && cjk)) {
                    sb.append(' ');
                }
                pending = false;
            }
            sb.append(c);
            prev = c;
        }
        return sb.toString();
    }

    private static boolean isCjk(Character c) {
        if (c == null) {
            return false;
        }
        char ch = c;
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || ch >= 0x3000 && ch <= 0x303F  // CJK 标点
                || ch >= 0xFF00 && ch <= 0xFFEF; // 全角字符
    }
}
