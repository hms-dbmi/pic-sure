package edu.harvard.hms.dbmi.avillach.commons.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Shared-secret gate for internal endpoints: a mandatory token on top of network isolation. Validates {@code X-PIC-SURE-INTERNAL-TOKEN}
 * against the configured token with a constant-time comparison. A missing or mismatched token is answered with 403 and a body of
 * {@code {errorType:"FORBIDDEN", message:"Forbidden", requestId}}.
 *
 * <p>Fail-closed: when the configured token is blank or unset, every request is rejected, so there is no way to satisfy the filter while
 * the secret is unconfigured.
 *
 * <p>The filter has no path check of its own: it enforces the token on every request it is given, so a path form that the servlet container
 * normalizes into the registered pattern can never skip the gate. Scoping is done only by the container, through
 * {@link InternalTokenFilterRegistration#create(String)}.
 *
 * <p>Not a {@code @Component}: annotating it would let Spring Boot register it a second time with the default {@code /*} mapping, so it
 * must be registered only through {@link InternalTokenFilterRegistration}.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-PIC-SURE-INTERNAL-TOKEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String expectedToken;

    /**
     * Creates a filter that accepts only requests carrying {@code expectedToken}.
     *
     * @param expectedToken the configured shared secret; a null or blank value rejects every request
     */
    public InternalTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String provided = req.getHeader(HEADER);
        if (expectedToken == null || expectedToken.isBlank() || provided == null || !constantTimeEquals(expectedToken, provided)) {
            forbidden(res);
            return;
        }
        chain.doFilter(req, res);
    }

    private void forbidden(HttpServletResponse res) throws IOException {
        res.setStatus(HttpStatus.FORBIDDEN.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", "FORBIDDEN");
        body.put("message", "Forbidden");
        body.put("requestId", requestId == null ? "" : requestId);
        MAPPER.writeValue(res.getWriter(), body);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
