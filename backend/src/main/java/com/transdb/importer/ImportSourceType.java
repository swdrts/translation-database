package com.transdb.importer;

/** 导入来源：TABLE=整理好的对照表（json/csv/excel）；DOCUMENT=整本书/文档（epub/pdf/word 等，仅原文）。 */
public enum ImportSourceType {
    TABLE,
    DOCUMENT
}
