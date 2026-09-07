package com.transdb.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtFailFastTest {

    private static final String DEV_SECRET = "dev-only-secret-key-change-me-32bytes!";

    @Test
    void devSecretRejectedInProdProfile() {
        assertThatThrownBy(() -> new JwtService(DEV_SECRET, 24, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRANSDB_JWT_SECRET");
    }

    @Test
    void strongSecretAcceptedInProdProfile() {
        assertThatCode(() -> new JwtService("0123456789abcdef0123456789abcdef", 24, "prod"))
                .doesNotThrowAnyException();
    }

    @Test
    void devSecretAcceptedOutsideProdProfile() {
        assertThatCode(() -> new JwtService(DEV_SECRET, 24, "dev")).doesNotThrowAnyException();
        assertThatCode(() -> new JwtService(DEV_SECRET, 24, null)).doesNotThrowAnyException();
    }
}
