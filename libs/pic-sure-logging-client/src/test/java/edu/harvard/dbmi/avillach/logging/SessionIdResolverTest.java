package edu.harvard.dbmi.avillach.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionIdResolverTest {

    @Test
    void returnsHeaderValueWhenPresent() {
        assertEquals("my-session", SessionIdResolver.resolve("my-session", "10.0.0.1", "Mozilla/5.0"));
    }

    @Test
    void fallsBackToHashWhenHeaderIsNull() {
        String result = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0");
        assertNotNull(result);
        assertEquals(16, result.length(), "SHA-256 truncated hash should be 16 hex chars");
        // Should be valid hex
        assertDoesNotThrow(() -> Long.parseUnsignedLong(result, 16));
    }

    @Test
    void fallsBackToHashWhenHeaderIsEmpty() {
        String result = SessionIdResolver.resolve("", "10.0.0.1", "Mozilla/5.0");
        assertNotNull(result);
        assertEquals(16, result.length());
    }

    @Test
    void hashIsDeterministic() {
        String first = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0");
        String second = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0");
        assertEquals(first, second);
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        String hash1 = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0");
        String hash2 = SessionIdResolver.resolve(null, "10.0.0.2", "Mozilla/5.0");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void handlesNullIpAndUserAgent() {
        String result = SessionIdResolver.resolve(null, null, null);
        assertNotNull(result);
        assertEquals(16, result.length());
        // Should be the SHA-256 of "|"
        assertEquals(SessionIdResolver.sha256Hex("|", 16), result);
    }

    @Test
    void hashIsLowercaseHex() {
        String result = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0");
        assertTrue(result.matches("[0-9a-f]{16}"), "Hash should be 16 lowercase hex characters, got: " + result);
    }

    @Test
    void sameIpDifferentUserAgentProducesDifferentHash() {
        // Important for VPN scenarios where many users share an IP
        String hash1 = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0 (Windows NT 10.0)");
        String hash2 = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0 (Macintosh; Intel Mac OS X)");
        assertNotEquals(hash1, hash2);
    }
}
