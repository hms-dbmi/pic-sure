package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class BufferingFilterTest {

    private static final GatewayAuthScope SCOPE = new GatewayAuthScope(false, List.of(".*/query/[^/]+/(?:result|signed-url)/?$"));

    private static ServletInputStream stream(String s) {
        ByteArrayInputStream bais = new ByteArrayInputStream(s.getBytes());
        return new ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l) {}
        };
    }

    @Test
    void wrapsRequestSoBodyCanBeReadTwice() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/query");
        when(req.getInputStream()).thenReturn(stream("hello"));
        when(req.getContentLengthLong()).thenReturn(5L);

        new BufferingFilter(64 * 1024, SCOPE, new SimpleMeterRegistry()).doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), eq(resp));
        BufferedRequestWrapper wrapped = (BufferedRequestWrapper) captor.getValue();
        assertThat(new String(wrapped.getBody())).isEqualTo("hello");
    }

    @Test
    void overCapReturns413WithErrorBodyBeforeChainAndIncrementsMetric() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        BufferingFilter f = new BufferingFilter(8, SCOPE, metrics); // tiny cap
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/query");
        when(req.getContentLengthLong()).thenReturn(100L);
        when(req.getInputStream()).thenReturn(stream("this body is well over the tiny cap"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(resp.getContentAsString()).contains("\"errorType\":\"REQUEST_BODY_TOO_LARGE\"")
            .contains("Request body exceeds the maximum size allowed for authorization processing.");
        verify(chain, never()).doFilter(any(), any()); // PSAMA never reached
        assertThat(metrics.counter("gateway.auth.body_too_large").count()).isEqualTo(1.0);
    }

    @Test
    void skipsInterimResultPath() throws Exception {
        BufferingFilter f = new BufferingFilter(64 * 1024, SCOPE, new SimpleMeterRegistry());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/query/abc/result");
        assertThat(f.shouldNotFilter(req)).isTrue();
    }
}
