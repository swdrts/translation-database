package com.transdb.importer.doc;

import java.util.List;

/** 电子书/文档解析的中间结果：识别到的书名、作者，以及按章节组织的原文段落。 */
public record ParsedDocument(String title, String author, List<DocChapter> chapters) {

    /** 一章：title 可为 null（无章节结构的文档整体视为一章、无章名）；paragraphs 为原文段落。 */
    public record DocChapter(String title, List<String> paragraphs) {
    }
}
