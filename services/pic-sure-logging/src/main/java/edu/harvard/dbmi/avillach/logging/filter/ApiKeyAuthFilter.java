package edu.harvard.dbmi.avillach.logging.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time X-API-Key check. Registered on /audit only.
 *
 * <p>Runs outside DispatcherServlet, so it writes its own response body: a @RestControllerAdvice cannot observe it.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final String BODY = "{\"status\":\"error\",\"message\":\"Missing or invalid API key\"}";

    private final byte[] expectedKeyBytes;
    private final boolean failClosed;

    public ApiKeyAuthFilter(String expectedApiKey) {
        this.failClosed = expectedApiKey == null || expectedApiKey.isBlank();
        this.expectedKeyBytes = failClosed ? new byte[0] : expectedApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String provided = request.getHeader(HEADER);
        if (failClosed || provided == null || provided.isBlank()) {
            reject(response);
            return;
        }

        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedKeyBytes, providedBytes)) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}
