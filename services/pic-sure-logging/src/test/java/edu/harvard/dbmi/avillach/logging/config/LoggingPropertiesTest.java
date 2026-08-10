package edu.harvard.dbmi.avillach.logging.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingPropertiesTest {

    private static LoggingProperties withApiKey(String apiKey) {
        return new LoggingProperties(apiKey, null, null, null, null, null, null);
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
}
