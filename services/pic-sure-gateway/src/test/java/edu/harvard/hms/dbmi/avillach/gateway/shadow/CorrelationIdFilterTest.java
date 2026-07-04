package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthMode;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import edu.harvard.hms.dbmi.avillach.gateway.config.RouteSurfaces;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The correlation id is minted + propagated EXACTLY where a {@code SHADOW_GW} record will be emitted: OBSERVE mode on the legacy catch-all
 * surface. It is a pure no-op in ENFORCE and TRANSPARENT (the production enforce path must forward no extra header) and on OBSERVE
 * gateway-owned routes (which enforce, and have no WildFly pair to correlate against). {@code /picsure/query/sync} is a catch-all path;
 * {@code /hpds/...} is a gateway-owned route.
 */
class CorrelationIdFilterTest {

    private static final GatewayModeResolver OBSERVE = new GatewayModeResolver(GatewayAuthMode.OBSERVE, RouteSurfaces.withDefaults());
    private static final GatewayModeResolver ENFORCE = new GatewayModeResolver(GatewayAuthMode.ENFORCE, RouteSurfaces.withDefaults());
    private static final GatewayModeResolver TRANSPARENT =
        new GatewayModeResolver(GatewayAuthMode.TRANSPARENT, RouteSurfaces.withDefaults());

    @Test
    void mintsAndPropagatesShadowIdHeaderWhenObserveCatchAll() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(OBSERVE);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/picsure/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenHeader = new String[1];
        Object[] seenAttribute = new Object[1];

        filter.doFilter(request, response, (req, resp) -> {
            seenHeader[0] = ((HttpServletRequest) req).getHeader(CorrelationIdFilter.HEADER);
            seenAttribute[0] = req.getAttribute(ShadowSupport.ATTR_CORRELATION_ID);
        });

        assertThat(seenHeader[0]).isNotBlank();
        assertThat(seenAttribute[0]).isEqualTo(seenHeader[0]);
    }

    @Test
    void doesNotMintWhenEnforce() throws Exception {
        // ENFORCE (incl. today's production topology) forwards no shadow header: byte-identical to pre-parity behavior.
        CorrelationIdFilter filter = new CorrelationIdFilter(ENFORCE);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/picsure/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenHeader = new String[1];
        Object[] seenAttribute = new Object[1];

        filter.doFilter(request, response, (req, resp) -> {
            seenHeader[0] = ((HttpServletRequest) req).getHeader(CorrelationIdFilter.HEADER);
            seenAttribute[0] = req.getAttribute(ShadowSupport.ATTR_CORRELATION_ID);
        });

        assertThat(seenHeader[0]).isNull();
        assertThat(seenAttribute[0]).isNull();
    }

    @Test
    void doesNotMintForOwnedRouteInObserve() throws Exception {
        // Gateway-owned routes enforce even in OBSERVE and emit no SHADOW_GW record, so no correlation id is minted.
        CorrelationIdFilter filter = new CorrelationIdFilter(OBSERVE);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/hpds/auth/v3/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenHeader = new String[1];
        Object[] seenAttribute = new Object[1];

        filter.doFilter(request, response, (req, resp) -> {
            seenHeader[0] = ((HttpServletRequest) req).getHeader(CorrelationIdFilter.HEADER);
            seenAttribute[0] = req.getAttribute(ShadowSupport.ATTR_CORRELATION_ID);
        });

        assertThat(seenHeader[0]).isNull();
        assertThat(seenAttribute[0]).isNull();
    }

    @Test
    void mintsADifferentIdPerRequest() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(OBSERVE);

        // both catch-all paths
        String first = capturedHeader(filter, "/a");
        String second = capturedHeader(filter, "/b");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void noHeaderOrAttributeWhenTransparent() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(TRANSPARENT);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/picsure/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenHeader = new String[1];
        Object[] seenAttribute = new Object[1];

        filter.doFilter(request, response, (req, resp) -> {
            seenHeader[0] = ((HttpServletRequest) req).getHeader(CorrelationIdFilter.HEADER);
            seenAttribute[0] = req.getAttribute(ShadowSupport.ATTR_CORRELATION_ID);
        });

        assertThat(seenHeader[0]).isNull();
        assertThat(seenAttribute[0]).isNull();
    }

    private String capturedHeader(CorrelationIdFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];
        filter.doFilter(request, response, (req, resp) -> seen[0] = ((HttpServletRequest) req).getHeader(CorrelationIdFilter.HEADER));
        return seen[0];
    }
}
