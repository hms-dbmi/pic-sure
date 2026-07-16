package edu.harvard.dbmi.avillach.logging;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoggingClientTest {

    private HttpServer server;
    private int port;
    private final CopyOnWriteArrayList<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private CountDownLatch latch;

    static class ReceivedRequest {
        final String body;
        final String contentType;
        final String apiKey;
        final String authorization;
        final String requestId;
        final int responseCode;

        ReceivedRequest(String body, String contentType, String apiKey, String authorization, String requestId, int responseCode) {
            this.body = body;
            this.contentType = contentType;
            this.apiKey = apiKey;
            this.authorization = authorization;
            this.requestId = requestId;
            this.responseCode = responseCode;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        received.clear();
        latch = new CountDownLatch(1);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/audit", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");

            received.add(new ReceivedRequest(body, contentType, apiKey, authorization, requestId, 202));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            latch.countDown();
        });

        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private LoggingClient createClient() {
        return new LoggingClient(
            LoggingClientConfig.builder("http://localhost:" + port, "test-api-key").clientType("api").connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(5)).build()
        );
    }

    @Test
    void sendsEventToServer() throws Exception {
        LoggingClient client = createClient();

        client.send(LoggingEvent.builder("QUERY").action("execute").build());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Server should receive request");
        assertEquals(1, received.size());

        ReceivedRequest req = received.get(0);
        assertEquals("application/json", req.contentType);
        assertEquals("test-api-key", req.apiKey);
        assertTrue(req.body.contains("\"event_type\":\"QUERY\""));
        assertTrue(req.body.contains("\"action\":\"execute\""));
        // Default clientType from config should be applied
        assertTrue(req.body.contains("\"client_type\":\"api\""));
    }

    @Test
    void sendsAuthorizationAndRequestIdHeaders() throws Exception {
        LoggingClient client = createClient();

        client.send(LoggingEvent.builder("ACCESS").action("read").build(), "Bearer jwt-token-123", "req-abc-456");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Server should receive request");
        ReceivedRequest req = received.get(0);
        assertEquals("Bearer jwt-token-123", req.authorization);
        assertEquals("req-abc-456", req.requestId);
    }

    @Test
    void eventClientTypeOverridesConfigDefault() throws Exception {
        LoggingClient client = createClient();

        client.send(LoggingEvent.builder("LOGIN").clientType("auth").build());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Server should receive request");
        ReceivedRequest req = received.get(0);
        assertTrue(req.body.contains("\"client_type\":\"auth\""));
        assertFalse(req.body.contains("\"client_type\":\"api\""));
    }

    @Test
    void noOpClientDiscardsSilently() {
        LoggingClient client = LoggingClient.noOp();

        assertDoesNotThrow(() -> client.send(LoggingEvent.builder("TEST").build()));
        assertDoesNotThrow(() -> client.send(LoggingEvent.builder("TEST").build(), "Bearer token", "req-id"));
        assertDoesNotThrow(() -> client.send(null));
        assertFalse(client.isEnabled());
    }

    @Test
    void liveClientIsEnabled() {
        LoggingClient client = createClient();
        assertTrue(client.isEnabled());
    }

    @Test
    void handleNullEventGracefully() {
        LoggingClient client = createClient();
        assertDoesNotThrow(() -> client.send(null));
    }

    @Test
    void handlesConnectionFailureGracefully() throws Exception {
        LoggingClient client = new LoggingClient(
            LoggingClientConfig.builder("http://192.0.2.1:1", "key").connectTimeout(Duration.ofMillis(200))
                .requestTimeout(Duration.ofMillis(500)).build()
        );

        assertDoesNotThrow(() -> client.send(LoggingEvent.builder("TEST").action("fail").build()));

        CountDownLatch errorLatch = new CountDownLatch(1);
        errorLatch.await(1, TimeUnit.SECONDS);
    }

    @Test
    void sendsRequestInfoInBody() throws Exception {
        LoggingClient client = createClient();

        RequestInfo request =
            RequestInfo.builder().method("POST").url("/query/sync").srcIp("192.168.1.1").status(200).duration(150L).build();

        client.send(LoggingEvent.builder("QUERY").action("execute").request(request).metadata(Map.of("resourceId", "uuid-123")).build());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Server should receive request");
        ReceivedRequest req = received.get(0);
        assertTrue(req.body.contains("\"method\":\"POST\""));
        assertTrue(req.body.contains("\"url\":\"/query/sync\""));
        assertTrue(req.body.contains("\"src_ip\":\"192.168.1.1\""));
        assertTrue(req.body.contains("\"status\":200"));
        assertTrue(req.body.contains("\"duration\":150"));
        assertTrue(req.body.contains("\"resourceId\":\"uuid-123\""));
    }

    @Test
    void handlesServerErrorGracefully() throws Exception {
        server.removeContext("/audit");
        server.createContext("/audit", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            latch.countDown();
        });

        LoggingClient client = createClient();

        assertDoesNotThrow(() -> client.send(LoggingEvent.builder("TEST").build()));
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Server should receive request");
    }
}
