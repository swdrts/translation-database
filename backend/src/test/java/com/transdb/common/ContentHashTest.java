package com.transdb.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHashTest {

    @Test
    void concatenatedFragmentsDoNotCollide() {
        assertThat(ContentHash.sha256("ab", "c"))
                .isNotEqualTo(ContentHash.sha256("a", "bc"));
    }

    @Test
    void hashIsStableHex64() {
        String h = ContentHash.sha256("学而时习之", "To learn and practice");
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ContentHash.sha256("学而时习之", "To learn and practice")).isEqualTo(h);
    }
}
