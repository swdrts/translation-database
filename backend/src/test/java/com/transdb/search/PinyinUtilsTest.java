package com.transdb.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PinyinUtilsTest {

    @Test
    void chineseCharsProduceFullPinyinAndFirstLetters() {
        assertThat(PinyinUtils.toFullPinyin("道德经")).isEqualTo("daodejing");
        assertThat(PinyinUtils.toFirstLetters("道德经")).isEqualTo("ddj");
        assertThat(PinyinUtils.toFullPinyin("论语")).isEqualTo("lunyu");
        assertThat(PinyinUtils.toFirstLetters("论语")).isEqualTo("ly");
    }

    @Test
    void latinAndDigitsPassThroughLowercased() {
        assertThat(PinyinUtils.toFullPinyin("论语LunYu1")).isEqualTo("lunyulunyu1");
        assertThat(PinyinUtils.toFirstLetters("论语 Lun")).isEqualTo("lyl");
    }

    @Test
    void blankAndNullAreEmpty() {
        assertThat(PinyinUtils.toFullPinyin(null)).isEmpty();
        assertThat(PinyinUtils.toFirstLetters("  ")).isEmpty();
    }
}
