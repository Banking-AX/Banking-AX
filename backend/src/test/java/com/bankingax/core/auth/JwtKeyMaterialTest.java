package com.bankingax.core.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JwtKeyMaterialTest {
    @Test
    void rejectsMissingShortPlaceholderAndLowDiversitySecrets() {
        assertThrows(IllegalStateException.class, () -> JwtKeyMaterial.from(""));
        assertThrows(IllegalStateException.class, () -> JwtKeyMaterial.from("short-secret"));
        assertThrows(IllegalStateException.class,
                () -> JwtKeyMaterial.from("replace-with-at-least-32-random-bytes"));
        assertThrows(IllegalStateException.class, () -> JwtKeyMaterial.from("a".repeat(40)));
    }

    @Test
    void acceptsAThirtyTwoByteNonPlaceholderSecret() {
        assertDoesNotThrow(() -> JwtKeyMaterial.from("test-only-secret-0123456789-ABCDEF"));
    }
}
