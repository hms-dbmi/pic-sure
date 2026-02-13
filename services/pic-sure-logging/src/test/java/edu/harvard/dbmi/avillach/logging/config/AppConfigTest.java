package edu.harvard.dbmi.avillach.logging.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private Function<String, String> envWith(Map<String, String> vars) {
        return vars::get;
    }

    @Test
    void allVarsSet() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "test-key");
        env.put("APP", "myapp");
        env.put("PLATFORM", "myplatform");
        env.put("ENVIRONMENT", "staging");
        env.put("HOSTNAME", "myhost");
        env.put("PORT", "9090");
        env.put("ALLOWED_ORIGIN", "https://example.com");
        env.put("JWT_CLAIM_MAPPING", "{\"sub\":\"subject\",\"email\":\"mail\"}");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals("test-key", config.auditApiKey());
        assertEquals("myapp", config.app());
        assertEquals("myplatform", config.platform());
        assertEquals("staging", config.environment());
        assertEquals("myhost", config.hostname());
        assertEquals(9090, config.port());
        assertEquals("https://example.com", config.allowedOrigin());
        assertEquals(Map.of("sub", "subject", "email", "mail"), config.jwtClaimMapping());
    }

    @Test
    void missingApiKeyFails() {
        Map<String, String> env = new HashMap<>();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("AUDIT_API_KEY"));
    }

    @Test
    void blankApiKeyFails() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "   ");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("AUDIT_API_KEY"));
    }

    @Test
    void invalidPortFails() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "test-key");
        env.put("PORT", "not-a-number");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("PORT"));
    }

    @Test
    void portOutOfRangeFails() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "test-key");
        env.put("PORT", "99999");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("PORT"));
    }

    @Test
    void defaultsUsedWhenVarsNotSet() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "test-key");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals("unknown", config.app());
        assertEquals("unknown", config.platform());
        assertEquals("unknown", config.environment());
        assertEquals(8080, config.port());
        assertEquals("*", config.allowedOrigin());
        assertNotNull(config.hostname());
        assertFalse(config.jwtClaimMapping().isEmpty());
    }

    @Test
    void defaultJwtClaimMappingUsed() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "test-key");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals("subject", config.jwtClaimMapping().get("sub"));
        assertEquals("user_email", config.jwtClaimMapping().get("email"));
        assertEquals("roles", config.jwtClaimMapping().get("roles"));
        assertEquals("logged_in", config.jwtClaimMapping().get("logged_in"));
    }

    @Test
    void customJwtClaimMappingParsed() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "test-key");
        env.put("JWT_CLAIM_MAPPING", "{\"custom_claim\":\"output_field\"}");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals(Map.of("custom_claim", "output_field"), config.jwtClaimMapping());
    }

    @Test
    void invalidJwtClaimMappingFails() {
        Map<String, String> env = new HashMap<>();
        env.put("AUDIT_API_KEY", "test-key");
        env.put("JWT_CLAIM_MAPPING", "not-json");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("JWT_CLAIM_MAPPING"));
    }
}
