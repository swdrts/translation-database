package com.transdb.exporter;

import java.util.List;

/** 导出预览统计：成书完成度 = pairedUnits / units（配对口径，非"有译文段/总段数"）。 */
public record BookStats(int segments, int units, int pairedUnits, List<ChapterStat> chapters) {

    public record ChapterStat(String title, int full, int src, int dst, int paired,
                              List<String> warnings) {
    }
}
