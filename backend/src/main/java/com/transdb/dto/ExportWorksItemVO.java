package com.transdb.dto;

/** 成书导出·书单行。 */
public record ExportWorksItemVO(String workTitle, long chapters, long totalSegments, long translatedSegments) {
}
