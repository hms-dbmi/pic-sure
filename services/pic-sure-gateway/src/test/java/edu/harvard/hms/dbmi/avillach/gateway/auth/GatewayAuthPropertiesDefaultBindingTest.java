package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the actual {@code application.yml} default (no property override): {@code picsure.gateway.security.mode} resolves to
 * {@code transparent} out of the box, keeping the gateway a pure pass-through until a later task wires OBSERVE/ENFORCE behavior.
 */
@SpringBootTest
class GatewayAuthPropertiesDefaultBindingTest {

    @Autowired
    private GatewayAuthProperties props;

    @Test
    void defaultApplicationYmlModeIsTransparent() {
        assertThat(props.getMode()).isEqualTo(GatewayAuthMode.TRANSPARENT);
    }
}
