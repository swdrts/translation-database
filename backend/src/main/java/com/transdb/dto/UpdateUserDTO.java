package com.transdb.dto;

import com.transdb.domain.Role;
import com.transdb.domain.UserStatus;

public record UpdateUserDTO(String displayName, Role role, UserStatus status) {
}
