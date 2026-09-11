package com.transdb.dto;

/** 导入预览会话的当前统计：总会话行数与按 planType 的计数。 */
public record ImportEditStatsVO(int totalRows, int willImportRows, int overwriteRows, int skippedRows) {
}
