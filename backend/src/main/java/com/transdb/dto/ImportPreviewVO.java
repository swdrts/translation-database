package com.transdb.dto;

import java.util.List;

public record ImportPreviewVO(String previewId, String strategy, int totalRows,
                              int willImportRows, int overwriteRows, int skippedRows,
                              List<LineError> errors, List<LineError> duplicates) {
}
