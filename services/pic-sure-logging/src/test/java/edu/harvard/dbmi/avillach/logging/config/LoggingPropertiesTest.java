package edu.harvard.dbmi.avillach.logging.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingPropertiesTest {

    private static LoggingProperties withApiKey(String apiKey) {
        return new LoggingProperties(apiKey, null, null, null, null, null, null);
    }

    private static LoggingProperties withJwtClaimMapping(Map<String, String> jwtClaimMapping) {
        return new LoggingProperties("k", null, null, null, null, null, jwtClaimMapping);
    }

    @Test
    void blankApiKeyFailsFast() {
        assertThatThrownBy(() -> withApiKey("   ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("LOGGING_API_KEY");
    }

    @Test
    void nullApiKeyFailsFast() {
        assertThatThrownBy(() -> withApiKey(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("LOGGING_API_KEY");
    }

    @Test
    void defaultsApplied() {
        LoggingProperties props = withApiKey("k");

        assertThat(props.app()).isEqualTo("unknown");
        assertThat(props.platform()).isEqualTo("unknown");
        assertThat(props.environment()).isEqualTo("unknown");
        assertThat(props.allowedOrigin()).isEqualTo("*");
        assertThat(props.hostname()).isNotBlank();
        assertThat(props.jwtClaimMapping()).isEqualTo(JwtClaimMappingConverter.DEFAULT_MAPPING);
    }

    @Test
    void explicitValuesWin() {
        LoggingProperties props =
            new LoggingProperties("k", "myapp", "myplatform", "staging", "myhost", "https://example.com", Map.of("a", "b"));

        assertThat(props.app()).isEqualTo("myapp");
        assertThat(props.hostname()).isEqualTo("myhost");
        assertThat(props.allowedOrigin()).isEqualTo("https://example.com");
        assertThat(props.jwtClaimMapping()).isEqualTo(Map.of("a", "b"));
    }

    @Test
    void explicitlyEmptyMappingIsHonoured() {
        LoggingProperties props = new LoggingProperties("k", null, null, null, null, null, Map.of());

        assertThat(props.jwtClaimMapping()).isEmpty();
        assertThat(props.jwtClaimMapping()).isNotEqualTo(JwtClaimMappingConverter.DEFAULT_MAPPING);
    }

    @Test
    void nullInputClaimNameFailsFast() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put(null, "x");

        assertThatThrownBy(() -> withJwtClaimMapping(mapping)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("null");
    }

    @Test
    void nullOutputFieldNameFailsFast() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("sub", null);

        assertThatThrownBy(() -> withJwtClaimMapping(mapping)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("sub");
    }

    @Test
    void blankInputClaimNameFailsFast() {
        assertThatThrownBy(() -> withJwtClaimMapping(Map.of(" ", "subject"))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining(" ");
    }

    @Test
    void blankOutputFieldNameFailsFast() {
        assertThatThrownBy(() -> withJwtClaimMapping(Map.of("sub", " "))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("sub");
    }

    @Test
    void duplicateOutputFieldNameFailsFast() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("sub", "identity");
        mapping.put("email", "identity");

        assertThatThrownBy(() -> withJwtClaimMapping(mapping)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("identity");
    }

    @Test
    void reservedTimeOutputFieldNameFailsFast() {
        assertThatThrownBy(() -> withJwtClaimMapping(Map.of("sub", "_time"))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("_time");
    }

    @Test
    void reservedEventTypeOutputFieldNameFailsFast() {
        assertThatThrownBy(() -> withJwtClaimMapping(Map.of("sub", "event_type"))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("event_type");
    }

    @Test
    void reservedLoggedInOutputFieldNameFailsFast() {
        assertThatThrownBy(() -> withJwtClaimMapping(Map.of("sub", "logged_in"))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("logged_in");
    }

    @Test
    void reservedLoggedInInputClaimNameFailsFast() {
        assertThatThrownBy(() -> withJwtClaimMapping(Map.of("logged_in", "custom_login"))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_CLAIM_MAPPING").hasMessageContaining("logged_in");
    }

    @Test
    void customMappingIsImmutableAndPreservesIterationOrder() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("email", "user_email");
        mapping.put("sub", "subject");
        LoggingProperties props = withJwtClaimMapping(mapping);

        assertThat(props.jwtClaimMapping().keySet()).containsExactly("email", "sub");
        assertThatThrownBy(() -> props.jwtClaimMapping().put("name", "user_name")).isInstanceOf(UnsupportedOperationException.class);
    }
}
