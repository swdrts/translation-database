package com.transdb.importer;

public record ImportRowPlan(long rowId, PlanType type, ParsedRow row,
                            Long existingSegmentId, String contentHash, boolean edited) {

    public enum PlanType { IMPORT, OVERWRITE, SKIP }

    /** 编辑行内容（文本/章节变化）：保留 rowId 与去重结论，标记已编辑。 */
    public ImportRowPlan withRow(ParsedRow newRow) {
        return new ImportRowPlan(rowId, type, newRow, existingSegmentId, contentHash, true);
    }

    /** 重评后更新去重结论：保留 rowId/row/edited。 */
    public ImportRowPlan withType(PlanType newType, Long newExistingId, String newHash) {
        return new ImportRowPlan(rowId, newType, row, newExistingId, newHash, edited);
    }
}
