package com.transdb.dto;

import java.util.List;

/** 分段编辑器的分页数据。prevRowId 无上一段时为 -1；chapter 未分章为空串；planType 为 IMPORT/OVERWRITE/SKIP。 */
public record ImportRowsPageVO(ImportEditStatsVO stats, int page, int totalPages, List<RowVO> rows) {

    public record RowVO(long rowId, int seq, long prevRowId, String chapter, String text,
                        String planType, boolean edited) {
    }

    /** 单行写操作的返回：受影响行 + 最新统计。 */
    public record ImportRowOpResultVO(RowVO row, ImportEditStatsVO stats) {
    }

    /** 拆分操作的返回：上下两行 + 最新统计。 */
    public record ImportSplitResultVO(List<RowVO> rows, ImportEditStatsVO stats) {
    }
}
