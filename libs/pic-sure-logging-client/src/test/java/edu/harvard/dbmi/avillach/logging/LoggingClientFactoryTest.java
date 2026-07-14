package edu.harvard.dbmi.avillach.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoggingClientFactoryTest {

    @Test
    void returnsNoOpWhenEnvVarsNotSet() {
        // In the test environment, LOGGING_SERVICE_URL and LOGGING_API_KEY are not set,
        // so create() should return a no-op client.
        LoggingClient client = LoggingClientFactory.create("api");

        assertNotNull(client);
        assertFalse(client.isEnabled());
    }

    @Test
    void createHandlesNullClientType() {
        LoggingClient client = LoggingClientFactory.create(null);

        assertNotNull(client);
        assertFalse(client.isEnabled());
    }

    @Test
    void createHandlesEmptyClientType() {
        LoggingClient client = LoggingClientFactory.create("  ");

        assertNotNull(client);
        assertFalse(client.isEnabled());
    }
}
