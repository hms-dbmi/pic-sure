package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    void startupFailsWhenNoClientTypeIsConfigured() {
        assertThatThrownBy(() -> config.clientTypeAllowlist(List.of())).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GATEWAY_ALLOWED_CLIENT_TYPES");
    }

    @Test
    void startupFailsWhenEveryConfiguredClientTypeIsBlank() {
        assertThatThrownBy(() -> config.clientTypeAllowlist(Arrays.asList("", "  ", null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupFailsWhenHpdsIsConfiguredAsAClientType() {
        for (String listed : List.of("hpds", "HPDS")) {
            assertThatThrownBy(() -> config.clientTypeAllowlist(List.of("PYTHON_ADAPTER", "R_ADAPTER", "FRONTEND", listed))).as(listed)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'" + listed + "' is reserved");
        }
    }

    @Test
    void aSingleConfiguredClientTypeStartsTheGateway() {
        assertThat(config.clientTypeAllowlist(List.of("FRONTEND")).resolve("frontend")).contains("FRONTEND");
    }
}
