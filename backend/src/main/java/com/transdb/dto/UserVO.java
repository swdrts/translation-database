package com.transdb.dto;

import com.transdb.domain.Role;
import com.transdb.domain.SysUser;

public record UserVO(long id, String username, String displayName, Role role) {

    public static UserVO from(SysUser u) {
        return new UserVO(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole());
    }
}
