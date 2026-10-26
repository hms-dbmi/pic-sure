package edu.harvard.hms.dbmi.avillach.commons.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;

/**
 * Verifies that {@link InternalTokenFilter} enforces the token on every request it receives, whatever the raw URI or context path looks
 * like, because the servlet container's {@code /internal/*} registration is the only scoping.
 */
class InternalTokenFilterTest {

    private static final String TOKEN = "expected-secret";

    private static final String CHAIN_REACHED = "X-Chain-Reached";

    private MockHttpServletResponse run(String contextPath, String uri, String configured, String provided) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath(contextPath);
        request.setRequestURI(uri);
        if (provided != null) {
            request.addHeader(InternalTokenFilter.HEADER, provided);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new InternalTokenFilter(configured).doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setHeader(CHAIN_REACHED, "true");
        }
        return response;
    }

    private MockHttpServletResponse run(String uri, String configured, String provided) throws Exception {
        return run("", uri, configured, provided);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"/internal/search/auth", "/internal", "/x/../internal/search/auth", "//internal/search/open",
            "/internal;a=b/search/auth", "/configuration/"}
    )
    void missingTokenIsRejectedForAnyPathForm(String uri) throws Exception {
        MockHttpServletResponse response = run(uri, TOKEN, null);

        assertEquals(403, response.getStatus());
        assertNull(response.getHeader(CHAIN_REACHED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/search/auth", "/x/../internal/search/auth", "//internal/search/open"})
    void wrongTokenIsRejectedForAnyPathForm(String uri) throws Exception {
        MockHttpServletResponse response = run(uri, TOKEN, "other");

        assertEquals(403, response.getStatus());
        assertNull(response.getHeader(CHAIN_REACHED));
    }

    @Test
    void correctTokenPassesToTheChain() throws Exception {
        MockHttpServletResponse response = run("/internal/search/auth", TOKEN, TOKEN);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getHeader(CHAIN_REACHED));
    }

    @Test
    void blankConfiguredTokenRejectsEvenAMatchingBlankHeader() throws Exception {
        assertEquals(403, run("/internal/search/auth", "", "").getStatus());
        assertEquals(403, run("/internal/search/auth", null, "anything").getStatus());
    }

    @Test
    void tokenIsEnforcedUnderANonEmptyContextPath() throws Exception {
        MockHttpServletResponse response = run("/ops", "/ops/internal/queries/abc/dispatch", TOKEN, null);

        assertEquals(403, response.getStatus());
        assertNull(response.getHeader(CHAIN_REACHED));
    }

    @Test
    void validTokenPassesThroughUnderANonEmptyContextPath() throws Exception {
        MockHttpServletResponse response = run("/ops", "/ops/internal/queries/abc/dispatch", TOKEN, TOKEN);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getHeader(CHAIN_REACHED));
    }

    @Test
    void rejectionBodyCarriesTheErrorTypeMessageAndRequestId() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        try {
            String body = run("/internal/search/auth", TOKEN, null).getContentAsString();

            assertTrue(body.contains("\"errorType\":\"FORBIDDEN\""));
            assertTrue(body.contains("\"message\":\"Forbidden\""));
            assertTrue(body.contains("\"requestId\":\"req-42\""));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    @Test
    void registrationIsScopedToTheInternalUrlPatternOnly() {
        FilterRegistrationBean<InternalTokenFilter> registration = InternalTokenFilterRegistration.create(TOKEN);

        assertEquals(List.of("/internal/*"), List.copyOf(registration.getUrlPatterns()));
        assertTrue(registration.getFilter() instanceof InternalTokenFilter);
        assertFalse(registration.getUrlPatterns().contains("/*"));
    }
}
