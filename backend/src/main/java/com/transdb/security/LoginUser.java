package com.transdb.security;

import com.transdb.domain.Role;

public record LoginUser(long id, String username, String displayName, Role role) {
}
