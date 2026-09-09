package com.transdb.dto;

import java.util.List;

/** 成书导出·配对预览；完成度 = pairedUnits / units（配对口径）。 */
public record ExportPreviewVO(long segments, int units, int pairedUnits, List<ChapterStatVO> chapters) {

    public record ChapterStatVO(String title, int full, int src, int dst, int paired, List<String> warnings) {
    }
}
