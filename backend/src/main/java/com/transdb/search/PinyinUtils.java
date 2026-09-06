package com.transdb.search;

import com.github.promeg.pinyinhelper.Pinyin;

public final class PinyinUtils {

    private PinyinUtils() {
    }

    /** 汉字→小写全拼连写；字母/数字原样小写；其余忽略。 */
    public static String toFullPinyin(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Pinyin.isChinese(c)) {
                String p = Pinyin.toPinyin(c);
                if (p != null) {
                    sb.append(p.toLowerCase());
                }
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 汉字取拼音首字母连写；字母/数字取每个连续片段的首字符小写；其余忽略。 */
    public static String toFirstLetters(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean prevLatin = false;
        for (char c : input.toCharArray()) {
            if (Pinyin.isChinese(c)) {
                String p = Pinyin.toPinyin(c);
                if (p != null) {
                    sb.append(Character.toLowerCase(p.charAt(0)));
                }
                prevLatin = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!prevLatin) {
                    sb.append(Character.toLowerCase(c));
                }
                prevLatin = true;
            } else {
                prevLatin = false;
            }
        }
        return sb.toString();
    }
}
