package com.bankingax.core.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

final class JwtKeyMaterial {
    private JwtKeyMaterial() { }

    static SecretKey from(String secret) {
        String value = secret == null ? "" : secret.trim();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String lower = value.toLowerCase(Locale.ROOT);
        if (bytes.length < 32 || lower.contains("replace") || lower.contains("change-me")
                || value.chars().distinct().count() < 8) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET must be a non-placeholder secret of at least 32 UTF-8 bytes.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
