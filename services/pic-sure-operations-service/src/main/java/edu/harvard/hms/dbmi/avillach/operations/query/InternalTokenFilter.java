package edu.harvard.hms.dbmi.avillach.operations.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Defense-in-depth gate for {@code /internal/**}: network isolation (not this class's job) PLUS a mandatory shared secret. Validates
 * {@code X-PIC-SURE-INTERNAL-TOKEN} against the configured {@code picsure.operations.internal-token} (env
 * {@code QUERY_SERVICE_INTERNAL_TOKEN}); missing/mismatched token -> 403 with {@code {errorType:"FORBIDDEN", message:"Forbidden",
 * requestId}}. <b>Fail-closed:</b> if the configured token is blank/unset, EVERY {@code /internal/**} call is rejected, including one that
 * happens to present a matching-looking header -- there is deliberately no way to satisfy this filter when the secret is unconfigured.
 *
 * <p>Registered as a plain {@code @Component} servlet {@link jakarta.servlet.Filter}: {@link #shouldNotFilter(HttpServletRequest)}
 * restricts all effects to {@code /internal/**}, so it runs independently of (and does not need to be woven into)
 * {@link edu.harvard.hms.dbmi.avillach.operations.config.WebSecurityConfig}'s Spring Security chain, which already {@code permitAll()}s
 * these paths at that layer -- this filter is what actually gates them.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-PIC-SURE-INTERNAL-TOKEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String expectedToken;

    public InternalTokenFilter(@Value("${picsure.operations.internal-token:}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
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
