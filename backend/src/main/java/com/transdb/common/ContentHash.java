package com.transdb.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentHash {

    private ContentHash() {
    }

    /** SHA-256 over UTF-8(sourceText + translatedText)，64 位小写 hex。 */
    public static String sha256(String sourceText, String translatedText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((sourceText + translatedText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
