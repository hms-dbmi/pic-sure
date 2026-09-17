package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OktaProvisioningConfigTest {

    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);

    private OktaProvisioningConfig buildConfig(String invokeUrl, String clientToken, String connectionId, boolean deprovisioningEnabled) {
        return new OktaProvisioningConfig(
            connectionRepository, invokeUrl, clientToken, connectionId, deprovisioningEnabled, "PICSURE", "PICSURE", "PIC-SURE User"
        );
    }

    @Test
    public void disabledWhenAllUnset() {
        OktaProvisioningConfig config = buildConfig("", "", "", false);
        config.init();

        assertFalse(config.isEnabled());
        assertFalse(config.isDeprovisioningEnabled());
    }

    @Test
    public void enabledWhenAllSet() {
        Connection connection = new Connection().setId("OKTA_CONN");
        when(connectionRepository.findById("OKTA_CONN")).thenReturn(Optional.of(connection));

        OktaProvisioningConfig config = buildConfig("https://example.okta.com/api/flo/xyz/invoke", "secret-token", "OKTA_CONN", false);
        config.init();

        assertTrue(config.isEnabled());
        assertEquals(connection, config.getConnection());
    }

    @Test
    public void deprovisioningRequiresProvisioningEnabled() {
        OktaProvisioningConfig config = buildConfig("", "", "", true);
        config.init();

        assertFalse(config.isEnabled());
        assertFalse(config.isDeprovisioningEnabled());
    }

    @Test
    public void deprovisioningEnabledWhenAllFlagsSet() {
        when(connectionRepository.findById("OKTA_CONN")).thenReturn(Optional.of(new Connection().setId("OKTA_CONN")));

        OktaProvisioningConfig config = buildConfig("https://example.okta.com/api/flo/xyz/invoke", "secret-token", "OKTA_CONN", true);
        config.init();

        assertTrue(config.isDeprovisioningEnabled());
    }

    @Test
    public void failsLoudlyWhenOnlyUrlIsSet() {
        OktaProvisioningConfig config = buildConfig("https://example.okta.com/api/flo/xyz/invoke", "", "", false);

        assertThrows(IllegalStateException.class, config::init);
    }

    @Test
    public void failsLoudlyWhenOnlyTokenIsSet() {
        OktaProvisioningConfig config = buildConfig("", "secret-token", "", false);

        assertThrows(IllegalStateException.class, config::init);
    }

    @Test
    public void failsLoudlyWhenOnlyConnectionIdIsSet() {
        OktaProvisioningConfig config = buildConfig("", "", "OKTA_CONN", false);

        assertThrows(IllegalStateException.class, config::init);
    }

    @Test
    public void failsLoudlyWhenConnectionIdDoesNotResolve() {
        when(connectionRepository.findById("MISSING")).thenReturn(Optional.empty());

        OktaProvisioningConfig config = buildConfig("https://example.okta.com/api/flo/xyz/invoke", "secret-token", "MISSING", false);

        assertThrows(IllegalStateException.class, config::init);
    }
}
