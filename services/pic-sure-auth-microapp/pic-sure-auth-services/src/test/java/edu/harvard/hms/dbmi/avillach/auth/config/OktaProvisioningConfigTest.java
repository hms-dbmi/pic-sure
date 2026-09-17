package edu.harvard.hms.dbmi.avillach.auth.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OktaProvisioningConfigTest {

    private OktaProvisioningConfig buildConfig(String invokeUrl, String clientToken, boolean deprovisioningEnabled) {
        return new OktaProvisioningConfig(invokeUrl, clientToken, deprovisioningEnabled, "PICSURE", "PICSURE", "PIC-SURE User");
    }

    @Test
    public void disabledWhenBothUnset() {
        OktaProvisioningConfig config = buildConfig("", "", false);
        config.init();

        assertFalse(config.isEnabled());
        assertFalse(config.isDeprovisioningEnabled());
    }

    @Test
    public void enabledWhenBothSet() {
        OktaProvisioningConfig config = buildConfig("https://example.okta.com/api/flo/xyz/invoke", "secret-token", false);
        config.init();

        assertTrue(config.isEnabled());
    }

    @Test
    public void deprovisioningRequiresProvisioningEnabled() {
        OktaProvisioningConfig config = buildConfig("", "", true);
        config.init();

        assertFalse(config.isEnabled());
        assertFalse(config.isDeprovisioningEnabled());
    }

    @Test
    public void deprovisioningEnabledWhenBothFlagsSet() {
        OktaProvisioningConfig config = buildConfig("https://example.okta.com/api/flo/xyz/invoke", "secret-token", true);
        config.init();

        assertTrue(config.isDeprovisioningEnabled());
    }

    @Test
    public void failsLoudlyWhenOnlyUrlIsSet() {
        OktaProvisioningConfig config = buildConfig("https://example.okta.com/api/flo/xyz/invoke", "", false);

        assertThrows(IllegalStateException.class, config::init);
    }

    @Test
    public void failsLoudlyWhenOnlyTokenIsSet() {
        OktaProvisioningConfig config = buildConfig("", "secret-token", false);

        assertThrows(IllegalStateException.class, config::init);
    }
}
