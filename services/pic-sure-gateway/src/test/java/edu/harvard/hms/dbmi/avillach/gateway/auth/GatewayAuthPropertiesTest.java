package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Task 2: {@code picsure.gateway.security.mode} binds into {@link GatewayAuthProperties#getMode()}. Deliberately bound on the SAME prefix
 * as {@code GatewaySecurityProperties.authEnabled} (see that class) without touching it -- this is a purely additive property.
 */
@SpringBootTest
@TestPropertySource(properties = "picsure.gateway.security.mode=observe")
class GatewayAuthPropertiesTest {

    @Autowired
    private GatewayAuthProperties props;

    @Test
    void bindsObserveModeFromProperty() {
        assertThat(props.getMode()).isEqualTo(GatewayAuthMode.OBSERVE);
    }

    @Test
    void recordAccessorAndGetterAgree() {
        assertThat(props.mode()).isEqualTo(props.getMode());
    }

    @Test
    void compactConstructorDefaultsNullModeToTransparent() {
        assertThat(new GatewayAuthProperties(null).mode()).isEqualTo(GatewayAuthMode.TRANSPARENT);
    }

    @Test
    void getModeAliasesRecordAccessor() {
        assertThat(new GatewayAuthProperties(GatewayAuthMode.ENFORCE).getMode()).isEqualTo(GatewayAuthMode.ENFORCE);
    }
}
