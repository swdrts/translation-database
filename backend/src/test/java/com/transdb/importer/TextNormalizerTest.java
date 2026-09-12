package com.transdb.importer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CJK 空格归一化：去除汉字之间的空白（OCR/EPUB 排版常见），保留拉丁词间空格。 */
class TextNormalizerTest {

    @Test
    void removesSpacesBetweenHanCharacters() {
        assertThat(TextNormalizer.normalizeCjkSpaces("此 开 卷 第 一 回 也 。"))
                .isEqualTo("此开卷第一回也。");
        assertThat(TextNormalizer.normalizeCjkSpaces("第 一 回"))
                .isEqualTo("第一回");
    }

    @Test
    void keepsLatinWordSpaces() {
        assertThat(TextNormalizer.normalizeCjkSpaces("Is it not a pleasure to learn?"))
                .isEqualTo("Is it not a pleasure to learn?");
        assertThat(TextNormalizer.normalizeCjkSpaces("论语 Confucius 论语"))
                .isEqualTo("论语 Confucius 论语");
    }

    @Test
    void handlesMixedContent() {
        // CJK 与拉丁数字相邻处的空格保留一个，便于阅读
        assertThat(TextNormalizer.normalizeCjkSpaces("第 1 回 The Return"))
                .isEqualTo("第 1 回 The Return");
        assertThat(TextNormalizer.normalizeCjkSpaces("红楼梦 第 一 回"))
                .isEqualTo("红楼梦第一回");
    }

    @Test
    void nullAndBlankSafe() {
        assertThat(TextNormalizer.normalizeCjkSpaces(null)).isNull();
        assertThat(TextNormalizer.normalizeCjkSpaces("  ")).isEqualTo("  ");
    }
}
