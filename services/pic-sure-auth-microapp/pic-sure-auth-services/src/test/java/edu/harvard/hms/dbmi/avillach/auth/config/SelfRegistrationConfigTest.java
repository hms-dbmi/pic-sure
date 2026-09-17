package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SelfRegistrationConfigTest {

    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);

    @Test
    public void disabledWhenConnectionIdIsBlank() {
        SelfRegistrationConfig config = new SelfRegistrationConfig(connectionRepository, "");
        config.init();

        assertFalse(config.isEnabled());
    }

    @Test
    public void enabledWhenConnectionIdResolves() {
        Connection connection = new Connection().setId("SELF_REG").setUuid(UUID.randomUUID());
        when(connectionRepository.findById("SELF_REG")).thenReturn(Optional.of(connection));

        SelfRegistrationConfig config = new SelfRegistrationConfig(connectionRepository, "SELF_REG");
        config.init();

        assertTrue(config.isEnabled());
        assertEquals(connection, config.getConnection());
    }

    @Test
    public void failsLoudlyWhenConnectionIdDoesNotResolve() {
        when(connectionRepository.findById("MISSING")).thenReturn(Optional.empty());

        SelfRegistrationConfig config = new SelfRegistrationConfig(connectionRepository, "MISSING");

        assertThrows(IllegalStateException.class, config::init);
    }
}
