package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthMode;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthProperties;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Task 3: in any non-TRANSPARENT mode the filter mints a correlation id, stores it under {@link ShadowSupport#ATTR_CORRELATION_ID} for
 * downstream Phase-2 filters, and propagates it to the forwarded request via the {@link CorrelationIdFilter#HEADER} header. In the default
 * TRANSPARENT mode it is a pure no-op.
 */
class CorrelationIdFilterTest {

    @Test
    void mintsAndPropagatesShadowIdHeaderWhenObserve() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(new GatewayAuthProperties(GatewayAuthMode.OBSERVE));
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
    void mintsAndPropagatesShadowIdHeaderWhenEnforce() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(new GatewayAuthProperties(GatewayAuthMode.ENFORCE));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/picsure/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenHeader = new String[1];

        filter.doFilter(request, response, (req, resp) -> seenHeader[0] = ((HttpServletRequest) req).getHeader(CorrelationIdFilter.HEADER));

        assertThat(seenHeader[0]).isNotBlank();
    }

    @Test
    void mintsADifferentIdPerRequest() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(new GatewayAuthProperties(GatewayAuthMode.OBSERVE));

        String first = capturedHeader(filter, "/a");
        String second = capturedHeader(filter, "/b");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void noHeaderOrAttributeWhenTransparent() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter(new GatewayAuthProperties(GatewayAuthMode.TRANSPARENT));
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
