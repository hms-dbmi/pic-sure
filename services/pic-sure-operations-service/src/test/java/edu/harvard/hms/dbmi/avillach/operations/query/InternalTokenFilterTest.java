package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * Pure unit tests for {@link InternalTokenFilter}: no Spring context, direct {@code doFilter} calls, matching the pattern used by
 * {@code GatewayPrivilegesFilterTest}.
 */
class InternalTokenFilterTest {

    private final InternalTokenFilter filter = new InternalTokenFilter("secret");

    private MockHttpServletRequest internalReq() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI("/internal/queries/abc/dispatch");
        return r;
    }

    @Test
    void validTokenPassesThrough() throws Exception {
        MockHttpServletRequest req = internalReq();
        req.addHeader(InternalTokenFilter.HEADER, "secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200); // chain proceeds; the real controller would set the actual body/status
    }

    @Test
    void missingTokenForbidden() throws Exception {
        MockHttpServletRequest req = internalReq();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"errorType\":\"FORBIDDEN\"").contains("\"message\":\"Forbidden\"")
            .contains("requestId");
    }

    @Test
    void wrongTokenForbidden() throws Exception {
        MockHttpServletRequest req = internalReq();
        req.addHeader(InternalTokenFilter.HEADER, "nope");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void unconfiguredTokenRejectsEvenAValidLookingCall() throws Exception {
        InternalTokenFilter unconfigured = new InternalTokenFilter("");
        MockHttpServletRequest req = internalReq();
        req.addHeader(InternalTokenFilter.HEADER, "anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        unconfigured.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void nonInternalPathSkipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/configuration/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    /**
     * Regression test for the finding this filter was hardened against: gating {@code /internal/**} by checking
     * {@code request.getRequestURI().startsWith("/internal/")} DIRECTLY breaks under a non-empty {@code server.servlet.context-path},
     * because {@code getRequestURI()} includes the context path prefix (here, {@code /ops}) and so never starts with the literal string
     * {@code "/internal/"} -- even though the container would still dispatch this exact request to {@code InternalQueryController} per the
     * {@code addUrlPatterns("/internal/*")} registration. {@link InternalTokenFilter} now strips {@code getContextPath()} off first, so it
     * agrees with the container's own routing decision regardless of context path.
     */
    @Test
    void tokenStillEnforcedUnderANonEmptyContextPath() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContextPath("/ops");
        req.setRequestURI("/ops/internal/queries/abc/dispatch");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void validTokenPassesThroughUnderANonEmptyContextPath() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContextPath("/ops");
        req.setRequestURI("/ops/internal/queries/abc/dispatch");
        req.addHeader(InternalTokenFilter.HEADER, "secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }
}
