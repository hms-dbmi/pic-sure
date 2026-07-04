package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * High-precedence filter for the parity-verification shadow pipeline (registered ahead of the auth chain -- see {@code SecurityConfig} --
 * so the correlation id exists before {@code OpenAccessFilter}/{@code PsamaIntrospectionFilter} emit their {@code SHADOW_GW} records).
 *
 * <p>Mints a UUID and propagates it -- storing it under {@link ShadowSupport#ATTR_CORRELATION_ID} for downstream filters and adding the
 * {@value #HEADER} header to the forwarded request so WildFly can correlate its own {@code side=WF} shadow line -- EXACTLY when the request
 * will emit a {@code SHADOW_GW} record: OBSERVE mode on the legacy catch-all surface ({@link GatewayModeResolver#observesFor}). It is a
 * pure no-op otherwise -- in ENFORCE and TRANSPARENT (so the production enforce path forwards no extra header), and on OBSERVE
 * gateway-owned routes (which enforce, and have no WildFly pair to correlate against).
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Correlation header propagated to the forwarded/downstream request; WildFly reads this back to pair its own shadow line. */
    public static final String HEADER = "X-PICSURE-Shadow-Id";

    private final GatewayModeResolver modeResolver;

    public CorrelationIdFilter(GatewayModeResolver modeResolver) {
        this.modeResolver = modeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!modeResolver.observesFor(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String correlationId = UUID.randomUUID().toString();
        request.setAttribute(ShadowSupport.ATTR_CORRELATION_ID, correlationId);
        chain.doFilter(new ShadowIdHeaderRequestWrapper(request, correlationId), response);
    }

    /** Overlays the {@value CorrelationIdFilter#HEADER} header onto the wrapped request for downstream readers. */
    private static final class ShadowIdHeaderRequestWrapper extends HttpServletRequestWrapper {

        private final String correlationId;

        ShadowIdHeaderRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            if (HEADER.equalsIgnoreCase(name)) {
                return correlationId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(correlationId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
