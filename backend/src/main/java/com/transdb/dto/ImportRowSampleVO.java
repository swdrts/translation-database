package com.transdb.dto;

/** 整本书导入预览的抽样段落：序号、所在章节与内容摘录。 */
public record ImportRowSampleVO(int line, String chapter, String text) {
}
