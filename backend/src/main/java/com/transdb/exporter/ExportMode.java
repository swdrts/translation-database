package com.transdb.exporter;

/** 导出内容模式；label 用于导出文件名（书名-label.ext）。 */
public enum ExportMode {
    TRANSLATION_ONLY("译文"),
    BILINGUAL("对照"),
    SOURCE_ONLY("原文");

    private final String label;

    ExportMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
