package edu.harvard.dbmi.avillach.logging.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter("test-api-key");
        request = new MockHttpServletRequest("POST", "/audit");
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @Test
    void validKeyPassesThrough() throws Exception {
        request.addHeader("X-API-Key", "test-api-key");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingHeaderReturns401AndStopsTheChain() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
    }

    @Test
    void blankHeaderReturns401() throws Exception {
        request.addHeader("X-API-Key", "   ");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void wrongKeyReturns401() throws Exception {
        request.addHeader("X-API-Key", "wrong-key");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void keyOfDifferentLengthReturns401() throws Exception {
        request.addHeader("X-API-Key", "test-api-key-plus-extra");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void blankConfiguredKeyFailsClosedForEveryRequest() throws Exception {
        ApiKeyAuthFilter failClosed = new ApiKeyAuthFilter("");
        request.addHeader("X-API-Key", "");

        failClosed.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
