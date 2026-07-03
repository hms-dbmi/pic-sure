package edu.harvard.dbmi.avillach.logging;

import edu.harvard.dbmi.avillach.logging.config.AppConfig;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract pin for the Javalin-native {@code GET /health} route (Phase 3 Task 8).
 *
 * <p>The gateway's system-status aggregation probes this endpoint directly (this service is
 * Javalin, not Spring Boot, so there is no Actuator {@code status:UP} body here). The contract
 * being pinned is: {@code 200 {"status":"healthy"}} when ready, {@code 503 {"status":"starting"}}
 * otherwise. This test exists so a future refactor of {@code App}/{@code HealthHandler} cannot
 * silently change that contract without a test failing.
 */
class HealthRouteTest {

    private static AppConfig testConfig() {
        return new AppConfig(
            "test-api-key", "testapp", "testplatform", "test", "testhost",
            0, "*",
            Map.of("sub", "subject", "email", "user_email", "roles", "roles", "logged_in", "logged_in")
        );
    }

    @Test
    void healthReturns200HealthyWhenReady() {
        Javalin app = App.createApp(testConfig(), new AtomicBoolean(true));
        JavalinTest.test(app, (server, client) -> {
            var resp = client.get("/health");
            assertEquals(200, resp.code());
            assertTrue(resp.body().string().contains("healthy"));
        });
    }

    @Test
    void healthReturns503StartingWhenNotReady() {
        Javalin app = App.createApp(testConfig(), new AtomicBoolean(false));
        JavalinTest.test(app, (server, client) -> {
            var resp = client.get("/health");
            assertEquals(503, resp.code());
            assertTrue(resp.body().string().contains("starting"));
        });
    }
}
