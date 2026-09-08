package com.transdb.dto;

import com.transdb.domain.SegmentStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SegmentUpsertDTO(
        @NotBlank(message = "不能为空") String sourceText,
        // 译文可留空：先录入原文存为待翻译草稿（状态强制 DRAFT），之后再补译文
        String translatedText,
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
