package com.teacai.wiki;

import com.teacai.wiki.service.PasswordService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordServiceTest {
    private final PasswordService passwords = new PasswordService();

    @Test void hashesAndVerifiesPasswords() {
        String hash = passwords.hashPassword("pāssword 🔑");
        assertTrue(hash.startsWith("pbkdf2-sha256$600000$"));
        assertFalse(hash.contains("pāssword"));
        assertTrue(passwords.verifyPassword("pāssword 🔑", hash));
        assertFalse(passwords.verifyPassword("wrong", hash));
        assertFalse(passwords.verifyPassword(null, hash));
        assertNotEquals(hash, passwords.hashPassword("pāssword 🔑"));
    }

    @Test void rejectsInvalidInputsAndMalformedHashes() {
        assertThrows(IllegalArgumentException.class, () -> passwords.hashPassword(""));
        assertThrows(IllegalArgumentException.class, () -> passwords.hashPassword("x".repeat(1025)));
        for (String hash : new String[]{"", "garbage", "pbkdf2-sha256$1$bad$bad", "pbkdf2-sha256$no$!$!"}) {
            assertFalse(passwords.verifyPassword("password", hash));
        }
        assertFalse(passwords.verifyPassword("password", null));
    }
}
