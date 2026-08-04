package edu.harvard.dbmi.avillach.logging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LoggingClientConfigTest {

    @Test
    void buildsWithValidValues() {
        LoggingClientConfig config = LoggingClientConfig.builder("http://pic-sure-logging:80/", "key").clientType("api")
            .connectTimeout(Duration.ofSeconds(1)).requestTimeout(Duration.ofSeconds(2)).build();

        assertEquals("http://pic-sure-logging:80", config.getBaseUrl(), "trailing slash should be normalized away");
        assertEquals(Duration.ofSeconds(1), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(2), config.getRequestTimeout());
    }

    @Test
    void rejectsNullOrBlankBaseUrlAndApiKey() {
        assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder(null, "key"));
        assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder("  ", "key"));
        assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder("http://host", null));
        assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder("http://host", "  "));
    }

    @Test
    void rejectsSchemelessOrNonHttpBaseUrl() {
        // Would otherwise only fail at send time: HttpRequest requires an absolute URI.
        assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder("pic-sure-logging:80", "key"));
        assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder("logging-service", "key"));
        assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder("ftp://host/audit", "key"));
    }

    @Test
    void rejectsMalformedBaseUrl() {
        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> LoggingClientConfig.builder("http://host with spaces", "key"));
        assertTrue(e.getMessage().contains("baseUrl"), "message should name the bad field, got: " + e.getMessage());
    }

    @Test
    void rejectsNullZeroOrNegativeTimeouts() {
        LoggingClientConfig.Builder builder = LoggingClientConfig.builder("http://host", "key");

        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder.requestTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.requestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.requestTimeout(Duration.ofSeconds(-1)));
    }
}
