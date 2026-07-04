package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthMode;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * High-precedence filter for the parity-verification shadow pipeline (registered ahead of the Phase-2 auth chain -- see
 * {@code SecurityConfig} -- so the correlation id exists before {@code PsamaIntrospectionFilter}/ {@code OpenAccessFilter} run). Note this
 * class does not itself branch behavior in those filters; a later task reads {@link ShadowSupport#ATTR_CORRELATION_ID} to do that.
 *
 * <p>When {@code picsure.gateway.security.mode} ({@link GatewayAuthProperties#getMode()}) is anything other than
 * {@link GatewayAuthMode#TRANSPARENT}, mints a UUID, stores it under {@link ShadowSupport#ATTR_CORRELATION_ID} for downstream filters, and
 * adds the {@value #HEADER} header to the request forwarded downstream so WildFly can correlate its own {@code side=WF} shadow log line. In
 * {@link GatewayAuthMode#TRANSPARENT} mode (the default) this is a pure no-op -- no attribute, no header -- matching the "pure proxy"
 * requirement for that mode.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Correlation header propagated to the forwarded/downstream request; WildFly (a later task) reads this back. */
    public static final String HEADER = "X-PICSURE-Shadow-Id";

    private final GatewayAuthProperties props;

    public CorrelationIdFilter(GatewayAuthProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (props.getMode() == GatewayAuthMode.TRANSPARENT) {
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
