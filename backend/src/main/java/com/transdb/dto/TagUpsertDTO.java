package com.transdb.dto;

import jakarta.validation.constraints.NotBlank;

public record TagUpsertDTO(
        @NotBlank(message = "不能为空") String name,
        String description) {
}
