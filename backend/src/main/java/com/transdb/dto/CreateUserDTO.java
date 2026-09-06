package com.transdb.dto;

import com.transdb.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserDTO(
        @NotBlank(message = "不能为空") String username,
        @NotBlank(message = "不能为空") @Size(min = 6, message = "至少 6 位") String password,
        String displayName,
        @NotNull(message = "不能为空") Role role) {
}
