package com.transdb.dto;

import java.util.List;

/**
 * 导入预览。sourceType=TABLE 时仅前八个字段有值；
 * DOCUMENT（整本书导入）时额外携带识别到的书名/作者、章节数与导入侧角色。
 */
public record ImportPreviewVO(String previewId, String strategy, int totalRows,
                              int willImportRows, int overwriteRows, int skippedRows,
                              List<LineError> errors, List<LineError> duplicates,
                              String sourceType, String documentTitle, String documentAuthor,
                              int chapterCount, String textRole) {
}
