package com.transdb.importer;

public record ImportRowPlan(PlanType type, ParsedRow row, Long existingSegmentId, String contentHash) {

    public enum PlanType { IMPORT, OVERWRITE, SKIP }
}
