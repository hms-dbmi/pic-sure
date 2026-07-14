package edu.harvard.dbmi.avillach.logging.middleware;

import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyAuthMiddlewareTest {

    private ApiKeyAuthMiddleware middleware;

    @BeforeEach
    void setUp() {
        middleware = new ApiKeyAuthMiddleware("test-api-key");
    }

    @Test
    void validKeyPasses() {
        Context ctx = mock(Context.class);
        when(ctx.header("X-API-Key")).thenReturn("test-api-key");

        assertDoesNotThrow(() -> middleware.authenticate(ctx));
    }

    @Test
    void missingHeaderThrows401() {
        Context ctx = mock(Context.class);
        when(ctx.header("X-API-Key")).thenReturn(null);

        assertThrows(UnauthorizedResponse.class, () -> middleware.authenticate(ctx));
    }

    @Test
    void blankHeaderThrows401() {
        Context ctx = mock(Context.class);
        when(ctx.header("X-API-Key")).thenReturn("   ");

        assertThrows(UnauthorizedResponse.class, () -> middleware.authenticate(ctx));
    }

    @Test
    void wrongKeyThrows401() {
        Context ctx = mock(Context.class);
        when(ctx.header("X-API-Key")).thenReturn("wrong-key");

        assertThrows(UnauthorizedResponse.class, () -> middleware.authenticate(ctx));
    }
}
