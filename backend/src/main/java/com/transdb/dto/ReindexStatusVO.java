package com.transdb.dto;

import java.time.Instant;

public record ReindexStatusVO(
        String state, long indexed, long total, Instant startedAt, Instant finishedAt, String error) {
}
