package com.transdb.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHashGoldenTest {

    @Test
    void goldenVectorPinsExactByteSequence() throws Exception {
        // 金标：独立构造期望值（sourceText + '\u0000' + translatedText 的 UTF-8 字节），
        // 钉死分隔符与拼接顺序——防止未来实现悄悄改变字节序列
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest("学而\u0000To learn".getBytes(StandardCharsets.UTF_8));
        assertThat(ContentHash.sha256("学而", "To learn"))
                .isEqualTo(HexFormat.of().formatHex(expected));
    }
}
