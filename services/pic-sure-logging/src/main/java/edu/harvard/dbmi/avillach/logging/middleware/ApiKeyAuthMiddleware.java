package edu.harvard.dbmi.avillach.logging.middleware;

import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ApiKeyAuthMiddleware {

    private final byte[] expectedKeyBytes;

    public ApiKeyAuthMiddleware(String expectedApiKey) {
        this.expectedKeyBytes = expectedApiKey.getBytes(StandardCharsets.UTF_8);
    }

    public void authenticate(Context ctx) {
        String provided = ctx.header("X-API-Key");
        if (provided == null || provided.isBlank()) {
            throw new UnauthorizedResponse("Missing or invalid API key");
        }

        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedKeyBytes, providedBytes)) {
            throw new UnauthorizedResponse("Missing or invalid API key");
        }
    }
}
