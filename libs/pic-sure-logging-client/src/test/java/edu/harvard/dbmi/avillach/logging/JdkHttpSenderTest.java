package edu.harvard.dbmi.avillach.logging;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class JdkHttpSenderTest {

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private static LoggingClientConfig config(String baseUrl, Duration connect, Duration request) {
        return LoggingClientConfig.builder(baseUrl, "test-key").connectTimeout(connect).requestTimeout(request).build();
    }

    @Test
    void dropsNewestWhenInFlightCapIsReached() throws Exception {
        // A ServerSocket that is never accepted from: the TCP handshake succeeds (backlog) but no HTTP
        // response ever arrives, so the first send parks in flight until the request timeout.
        try (ServerSocket server = new ServerSocket(0)) {
            LoggingClientConfig config = config("http://127.0.0.1:" + server.getLocalPort(), Duration.ofSeconds(2), Duration.ofSeconds(10));
            JdkHttpSender sender = new JdkHttpSender(config, 1);
            URI endpoint = URI.create(config.getBaseUrl() + "/audit");
            LoggingEvent event = LoggingEvent.builder("TEST").build();

            sender.send(BODY, endpoint, config, event, null, null); // occupies the single permit
            sender.send(BODY, endpoint, config, event, null, null);
            sender.send(BODY, endpoint, config, event, null, null);

            assertEquals(2, sender.droppedCount(), "sends past the in-flight cap should be dropped, not queued");
            assertEquals(0, sender.availablePermits());
        }
    }

    @Test
    void releasesPermitWhenSendCompletesExceptionally() throws Exception {
        // Connection refused: the async send completes exceptionally almost immediately and MUST return its permit.
        int refusedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            refusedPort = probe.getLocalPort();
        }
        LoggingClientConfig config = config("http://127.0.0.1:" + refusedPort, Duration.ofMillis(500), Duration.ofSeconds(1));
        JdkHttpSender sender = new JdkHttpSender(config, 1);
        URI endpoint = URI.create(config.getBaseUrl() + "/audit");

        sender.send(BODY, endpoint, config, LoggingEvent.builder("TEST").build(), null, null);

        long deadline = System.currentTimeMillis() + 5000;
        while (sender.availablePermits() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, sender.availablePermits(), "permit should be released after exceptional completion");
        assertEquals(0, sender.droppedCount());
    }
}
