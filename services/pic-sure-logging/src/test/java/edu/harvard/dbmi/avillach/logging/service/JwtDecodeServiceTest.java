package edu.harvard.dbmi.avillach.logging.service;

import edu.harvard.dbmi.avillach.logging.TestJwtBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtDecodeServiceTest {

    private static final Map<String, String> DEFAULT_MAPPING = Map.of(
        "sub", "subject",
        "email", "user_email",
        "name", "user_name",
        "roles", "roles",
        "logged_in", "logged_in"
    );

    private JwtDecodeService service;

    @BeforeEach
    void setUp() {
        service = new JwtDecodeService(DEFAULT_MAPPING);
    }

    @Test
    void validTokenExtractsClaims() {
        String token = TestJwtBuilder.buildToken(Map.of(
            "sub", "user123",
            "email", "user@example.com",
            "name", "John Doe"
        ));

        Map<String, Object> result = service.extractClaims("Bearer " + token);

        assertEquals("user123", result.get("subject"));
        assertEquals("user@example.com", result.get("user_email"));
        assertEquals("John Doe", result.get("user_name"));
        assertEquals(true, result.get("logged_in"));
    }

    @Test
    void nullTokenReturnsLoggedInFalse() {
        Map<String, Object> result = service.extractClaims(null);
        assertEquals(false, result.get("logged_in"));
        assertEquals(1, result.size());
    }

    @Test
    void blankTokenReturnsLoggedInFalse() {
        Map<String, Object> result = service.extractClaims("   ");
        assertEquals(false, result.get("logged_in"));
    }

    @Test
    void bearerPrefixOnlyReturnsLoggedInFalse() {
        Map<String, Object> result = service.extractClaims("Bearer ");
        assertEquals(false, result.get("logged_in"));
    }

    @Test
    void malformedTokenReturnsLoggedInFalse() {
        Map<String, Object> result = service.extractClaims("Bearer not.a.jwt");
        assertEquals(false, result.get("logged_in"));
    }

    @Test
    void tokenWithoutBearerPrefix() {
        String token = TestJwtBuilder.buildToken(Map.of("sub", "user123"));
        Map<String, Object> result = service.extractClaims(token);
        assertEquals("user123", result.get("subject"));
    }

    @Test
    void missingClaimsAreOmitted() {
        String token = TestJwtBuilder.buildToken(Map.of("sub", "user123"));
        Map<String, Object> result = service.extractClaims("Bearer " + token);

        assertEquals("user123", result.get("subject"));
        assertNull(result.get("user_email"));
        assertNull(result.get("user_name"));
    }

    @Test
    void arrayRolesPreservedAsList() {
        String token = TestJwtBuilder.buildToken(Map.of(
            "roles", List.of("ADMIN", "USER")
        ));

        Map<String, Object> result = service.extractClaims("Bearer " + token);

        Object roles = result.get("roles");
        assertInstanceOf(List.class, roles);
        assertEquals(List.of("ADMIN", "USER"), roles);
    }

    @Test
    void booleanLoggedInPreserved() {
        String token = TestJwtBuilder.buildToken(Map.of(
            "logged_in", true
        ));

        Map<String, Object> result = service.extractClaims("Bearer " + token);
        assertEquals(true, result.get("logged_in"));
    }

    @Test
    void expiredTokenStillDecodes() {
        String token = TestJwtBuilder.buildExpiredToken(Map.of(
            "sub", "user123",
            "email", "user@example.com"
        ));

        Map<String, Object> result = service.extractClaims("Bearer " + token);
        assertEquals("user123", result.get("subject"));
        assertEquals("user@example.com", result.get("user_email"));
    }

    @Test
    void customClaimMapping() {
        JwtDecodeService customService = new JwtDecodeService(Map.of(
            "custom_id", "my_id",
            "custom_email", "my_email"
        ));

        String token = TestJwtBuilder.buildToken(Map.of(
            "custom_id", "abc",
            "custom_email", "test@test.com"
        ));

        Map<String, Object> result = customService.extractClaims("Bearer " + token);
        assertEquals("abc", result.get("my_id"));
        assertEquals("test@test.com", result.get("my_email"));
    }

    // --- JWT size check tests (Change 4) ---

    @Test
    void oversizedTokenReturnsLoggedInFalse() {
        // Build a token string larger than 16KB
        String bigToken = "Bearer " + "a".repeat(17_000);
        Map<String, Object> result = service.extractClaims(bigToken);
        assertEquals(false, result.get("logged_in"));
        assertEquals(1, result.size());
    }

    @Test
    void tokenUnderSizeLimitStillWorks() {
        String token = TestJwtBuilder.buildToken(Map.of("sub", "user123"));
        // Normal tokens are well under 16KB
        assertTrue(token.length() < 16_384);
        Map<String, Object> result = service.extractClaims("Bearer " + token);
        assertEquals("user123", result.get("subject"));
        assertEquals(true, result.get("logged_in"));
    }
}
