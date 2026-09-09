package edu.harvard.dbmi.avillach.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.dbmi.avillach.logging.config.AppConfig;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest.BodyPublishers;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AppIntegrationTest {

    private static final String API_KEY = "integration-test-key";

    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;

    private AppConfig createTestConfig() {
        return new AppConfig(
            API_KEY, "testapp", "testplatform", "test", "testhost", 0, "*",
            Map.of("sub", "subject", "email", "user_email", "roles", "roles", "logged_in", "logged_in")
        );
    }

    @BeforeEach
    void setUp() {
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
    }

    @Test
    void validRequestReturns202() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response = client.request(
                "/audit",
                builder -> builder.post(BodyPublishers.ofString("{\"event_type\":\"QUERY\",\"action\":\"execute\"}"))
                    .header("X-API-Key", API_KEY).header("Content-Type", "application/json")
            );

            assertEquals(202, response.code());
            assertTrue(response.body().string().contains("accepted"));
            assertEquals(1, listAppender.list.size());
        });
    }

    @Test
    void missingApiKeyReturns401() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response = client.request(
                "/audit",
                builder -> builder.post(BodyPublishers.ofString("{\"event_type\":\"QUERY\"}")).header("Content-Type", "application/json")
            );

            assertEquals(401, response.code());
        });
    }

    @Test
    void wrongApiKeyReturns401() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response = client.request(
                "/audit",
                builder -> builder.post(BodyPublishers.ofString("{\"event_type\":\"QUERY\"}")).header("X-API-Key", "wrong-key")
                    .header("Content-Type", "application/json")
            );

            assertEquals(401, response.code());
        });
    }

    @Test
    void badJsonReturns400() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response = client.request(
                "/audit",
                builder -> builder.post(BodyPublishers.ofString("not-json")).header("X-API-Key", API_KEY)
                    .header("Content-Type", "application/json")
            );

            assertEquals(400, response.code());
        });
    }

    @Test
    void missingEventTypeReturns400() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response = client.request(
                "/audit",
                builder -> builder.post(BodyPublishers.ofString("{\"action\":\"execute\"}")).header("X-API-Key", API_KEY)
                    .header("Content-Type", "application/json")
            );

            assertEquals(400, response.code());
        });
    }

    @Test
    void healthReturns200WithoutAuth() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response = client.get("/health");

            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("healthy"));
        });
    }

    @Test
    void jwtClaimsAppearInLog() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            String token = TestJwtBuilder.buildToken(Map.of("sub", "user123", "email", "user@example.com"));

            Response response = client.request(
                "/audit",
                builder -> builder.post(BodyPublishers.ofString("{\"event_type\":\"QUERY\"}")).header("X-API-Key", API_KEY)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            );

            assertEquals(202, response.code());
            assertEquals(1, listAppender.list.size());
            String logMessage = listAppender.list.get(0).getFormattedMessage();
            assertTrue(logMessage.contains("subject=user123"));
            assertTrue(logMessage.contains("user_email=user@example.com"));
        });
    }

    @Test
    void requestIdFromHeaderAppearsInLog() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response = client.request(
                "/audit",
                builder -> builder.post(BodyPublishers.ofString("{\"event_type\":\"QUERY\"}")).header("X-API-Key", API_KEY)
                    .header("X-Request-Id", "req-abc-123").header("Content-Type", "application/json")
            );

            assertEquals(202, response.code());
            assertEquals(1, listAppender.list.size());
            String logMessage = listAppender.list.get(0).getFormattedMessage();
            assertTrue(logMessage.contains("request_id=req-abc-123"));
        });
    }

    // --- Health check readiness tests (Change 6) ---

    @Test
    void healthReturns503WhenNotReady() {
        AtomicBoolean readiness = new AtomicBoolean(false);
        Javalin app = App.createApp(createTestConfig(), readiness);
        JavalinTest.test(app, (server, client) -> {
            Response response = client.get("/health");

            assertEquals(503, response.code());
            assertTrue(response.body().string().contains("starting"));
        });
    }

    @Test
    void infoReturns200WithoutAuth() {
        Javalin app = App.createApp(createTestConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            Response response =
                client.request("/info", builder -> builder.post(BodyPublishers.ofString("{}")).header("Content-Type", "application/json"));

            assertEquals(200, response.code());
            String responseBody = response.body().string();
            assertTrue(responseBody.contains("Logging Service"));
            assertTrue(responseBody.contains("queryFormats"));
        });
    }

    @Test
    void healthReturns200WhenReady() {
        AtomicBoolean readiness = new AtomicBoolean(true);
        Javalin app = App.createApp(createTestConfig(), readiness);
        JavalinTest.test(app, (server, client) -> {
            Response response = client.get("/health");

            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("healthy"));
        });
    }
}
