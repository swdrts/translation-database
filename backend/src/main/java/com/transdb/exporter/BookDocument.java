package com.transdb.exporter;

import java.util.List;

/** 成书渲染模型：装配结果，供各 BookRenderer 消费。 */
public record BookDocument(String title, String author, String translator,
                           List<BookChapter> chapters) {

    /** 书中的一个对齐单元：source/translated 为 null 表示该侧缺失（渲染时用占位符）。 */
    public record BookUnit(String source, String translated) {

        public static final String UNTRANSLATED_PLACEHOLDER = "〔未译〕";
        public static final String MISSING_SOURCE_PLACEHOLDER = "〔原文缺失〕";

        public String sourceSafe() {
            return source != null ? source : MISSING_SOURCE_PLACEHOLDER;
        }

        public String translatedSafe() {
            return translated != null ? translated : UNTRANSLATED_PLACEHOLDER;
        }
    }

    public record BookChapter(String title, List<BookUnit> units) {
    }
}
