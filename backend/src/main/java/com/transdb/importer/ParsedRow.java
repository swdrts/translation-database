package com.transdb.importer;

import java.util.Map;

/** 一条解析后的数据行；fields 的键为规范化列名（如 source_text）。lineNumber 从 1 计（不含表头）。 */
public record ParsedRow(int lineNumber, Map<String, String> fields) {

    public String get(String column) {
        return fields.get(column);
    }
}
