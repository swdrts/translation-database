package com.transdb.dto;

import com.transdb.domain.SegmentStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SegmentUpsertDTO(
        @NotBlank(message = "不能为空") String sourceText,
        @NotBlank(message = "不能为空") String translatedText,
        String workTitle,
        String chapter,
        String author,
        String dynasty,
        String translator,
        String notes,
        SegmentStatus status,
        List<Long> tagIds,
        Long version) {
}
