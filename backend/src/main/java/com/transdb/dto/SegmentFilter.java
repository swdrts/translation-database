package com.transdb.dto;

import com.transdb.domain.SegmentStatus;

public record SegmentFilter(String work, String dynasty, Long tagId, SegmentStatus status) {
}
