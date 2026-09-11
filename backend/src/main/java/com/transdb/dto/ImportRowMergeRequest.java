package com.transdb.dto;

import java.util.List;

/** 合并连续分段：rowIds ≥2 且须为会话当前顺序中的连续段。 */
public record ImportRowMergeRequest(List<Long> rowIds) {
}
