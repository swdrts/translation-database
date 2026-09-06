package com.transdb.security;

import com.transdb.domain.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void generateThenParseRoundTrips() {
        JwtService service = new JwtService(SECRET, Duration.ofHours(24));
        LoginUser in = new LoginUser(42L, "admin", "管理员", Role.ADMIN);

        LoginUser out = service.parse(service.generate(in));

        assertThat(out.id()).isEqualTo(42L);
        assertThat(out.username()).isEqualTo("admin");
        assertThat(out.displayName()).isEqualTo("管理员");
        assertThat(out.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void expiredTokenRejected() {
        JwtService service = new JwtService(SECRET, Duration.ofMillis(-1000));
        String token = service.generate(new LoginUser(1L, "u", "u", Role.VIEWER));
        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedTokenRejected() {
        JwtService service = new JwtService(SECRET, Duration.ofHours(24));
        String token = service.generate(new LoginUser(1L, "u", "u", Role.VIEWER));
        String tampered = token.substring(0, token.length() - 3) + "abc";
        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }
}
