package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the actual {@code application.yml} default (no property override): {@code picsure.gateway.security.mode} is left UNSET (null) out of
 * the box -- absence is distinguishable from an explicit {@code transparent} so {@link GatewayModeResolver} can derive the effective mode
 * from {@code auth-enabled}. With auth-enabled also defaulting to false, the resolved effective mode is TRANSPARENT (pure pass-through),
 * exactly as before this resolved-mode work.
 */
@SpringBootTest
class GatewayAuthPropertiesDefaultBindingTest {

    @Autowired
    private GatewayAuthProperties props;

    @Autowired
    private GatewayModeResolver modeResolver;

    @Test
    void defaultApplicationYmlLeavesModeUnset() {
        assertThat(props.getMode()).isNull();
    }

    @Test
    void defaultEffectiveModeResolvesToTransparent() {
        assertThat(modeResolver.effectiveMode()).isEqualTo(GatewayAuthMode.TRANSPARENT);
    }
}
