package edu.harvard.hms.dbmi.avillach.operations.query;

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
import edu.harvard.hms.dbmi.avillach.operations.config.InternalTokenFilterConfig;
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
 * <p>NOT a {@code @Component}: it is registered exactly once, by {@link InternalTokenFilterConfig}, via a
 * {@code FilterRegistrationBean<InternalTokenFilter>} with {@code addUrlPatterns("/internal/*")}. That container-level URL-pattern match is
 * normalized/authoritative and (per the servlet spec) is always evaluated relative to the context path -- accounting for any servlet
 * context path and path normalization the same way the {@code DispatcherServlet} routing to {@link InternalQueryController} does, so the
 * two can never disagree about which requests are "internal".
 *
 * <p>{@link #shouldNotFilter(HttpServletRequest)} is a second, redundant guard on top of that container-level mapping
 * (belt-and-suspenders), not the primary gate -- but it MUST agree with the container's context-relative matching. It therefore strips
 * {@link HttpServletRequest#getContextPath()} off {@link HttpServletRequest#getRequestURI()} before comparing, rather than testing the raw
 * URI directly: {@code getRequestURI()} INCLUDES any context path, so under a non-empty {@code server.servlet.context-path} the raw URI
 * would never start with the literal string {@code "/internal/"} even for a request the container correctly dispatches to
 * {@link InternalQueryController} -- which used to make this exact check silently skip the token gate. (Deliberately not
 * {@code getServletPath()}: that is populated per the target servlet's own mapping style -- e.g. empty in some test/mock harnesses --
 * whereas context-path-stripping only ever depends on {@code getContextPath()}, which is reliable in both real containers and MockMvc.)
 *
 * <p>Runs independently of (and does not need to be woven into) {@link edu.harvard.hms.dbmi.avillach.operations.config.WebSecurityConfig}'s
 * Spring Security chain, which already {@code permitAll()}s these paths at that layer -- this filter is what actually gates them.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-PIC-SURE-INTERNAL-TOKEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String expectedToken;

    public InternalTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !contextRelativePath(request).startsWith("/internal/");
    }

    /** {@code request.getRequestURI()} with any {@code request.getContextPath()} prefix stripped off. */
    private static String contextRelativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
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
