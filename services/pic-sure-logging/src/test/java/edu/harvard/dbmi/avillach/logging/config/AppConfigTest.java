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
        env.put("LOGGING_API_KEY", "test-key");
        env.put("APP", "myapp");
        env.put("PLATFORM", "myplatform");
        env.put("ENVIRONMENT", "staging");
        env.put("HOSTNAME", "myhost");
        env.put("NIH_ICO", "NHLBI");
        env.put("CADR_NAME", "BDC - BioData Catalyst");
        env.put("PORT", "9090");
        env.put("ALLOWED_ORIGIN", "https://example.com");
        env.put("JWT_CLAIM_MAPPING", "{\"sub\":\"subject\",\"email\":\"mail\"}");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals("test-key", config.auditApiKey());
        assertEquals("myapp", config.app());
        assertEquals("myplatform", config.platform());
        assertEquals("staging", config.environment());
        assertEquals("myhost", config.hostname());
        assertEquals("NHLBI", config.nihIco());
        assertEquals("BDC - BioData Catalyst", config.cadrName());
        assertEquals(9090, config.port());
        assertEquals("https://example.com", config.allowedOrigin());
        assertEquals(Map.of("sub", "subject", "email", "mail"), config.jwtClaimMapping());
    }

    @Test
    void missingApiKeyFails() {
        Map<String, String> env = new HashMap<>();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("LOGGING_API_KEY"));
    }

    @Test
    void blankApiKeyFails() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "   ");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("LOGGING_API_KEY"));
    }

    @Test
    void invalidPortFails() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");
        env.put("PORT", "not-a-number");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("PORT"));
    }

    @Test
    void portOutOfRangeFails() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");
        env.put("PORT", "99999");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("PORT"));
    }

    @Test
    void defaultsUsedWhenVarsNotSet() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals("unknown", config.app());
        assertEquals("unknown", config.platform());
        assertEquals("unknown", config.environment());
        assertNull(config.nihIco());
        assertNull(config.cadrName());
        assertEquals(8080, config.port());
        assertEquals("*", config.allowedOrigin());
        assertNotNull(config.hostname());
        assertFalse(config.jwtClaimMapping().isEmpty());
    }

    @Test
    void blankCadrSettingsAreUnset() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");
        env.put("NIH_ICO", "   ");
        env.put("CADR_NAME", "");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertNull(config.nihIco());
        assertNull(config.cadrName());
    }

    @Test
    void defaultJwtClaimMappingHasExactlyTheExpectedEntries() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        Map<String, String> expected = Map.ofEntries(
            Map.entry("sub", "subject"), Map.entry("email", "user_email"), Map.entry("name", "user_name"), Map.entry("userid", "user_id"),
            Map.entry("preferred_username", "preferred_username"), Map.entry("org", "user_org"),
            Map.entry("country_name", "user_country_name"), Map.entry("nih_ico", "nih_ico"), Map.entry("eRA_commons_id", "eRA_commons_id"),
            Map.entry("user_permission_group", "user_permission_group"), Map.entry("uuid", "uuid"), Map.entry("roles", "roles"),
            Map.entry("idp", "user_id_provider"), Map.entry("cadr_name", "cadr_name")
        );
        assertEquals(expected, config.jwtClaimMapping());
    }

    @Test
    void defaultJwtClaimMappingUsed() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals("subject", config.jwtClaimMapping().get("sub"));
        assertEquals("user_email", config.jwtClaimMapping().get("email"));
        assertEquals("roles", config.jwtClaimMapping().get("roles"));
        assertEquals("user_id", config.jwtClaimMapping().get("userid"));
        assertEquals("preferred_username", config.jwtClaimMapping().get("preferred_username"));
        assertEquals("user_permission_group", config.jwtClaimMapping().get("user_permission_group"));
    }

    @Test
    void customJwtClaimMappingParsed() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");
        env.put("JWT_CLAIM_MAPPING", "{\"custom_claim\":\"output_field\"}");

        AppConfig config = AppConfig.fromEnvironment(envWith(env));

        assertEquals(Map.of("custom_claim", "output_field"), config.jwtClaimMapping());
    }

    @Test
    void invalidJwtClaimMappingFails() {
        Map<String, String> env = new HashMap<>();
        env.put("LOGGING_API_KEY", "test-key");
        env.put("JWT_CLAIM_MAPPING", "not-json");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AppConfig.fromEnvironment(envWith(env)));
        assertTrue(ex.getMessage().contains("JWT_CLAIM_MAPPING"));
    }
}
