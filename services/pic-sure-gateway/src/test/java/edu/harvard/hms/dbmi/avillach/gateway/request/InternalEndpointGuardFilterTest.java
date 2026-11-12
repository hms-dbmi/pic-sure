package edu.harvard.hms.dbmi.avillach.gateway.request;

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
 * {@link InternalEndpointGuardFilter} runs before routing/auth: {@code /operations/internal/**} and {@code /internal/**} are
 * service-to-service only and must never be reachable through the public gateway, even with a stolen internal token.
 */
class InternalEndpointGuardFilterTest {

    private final InternalEndpointGuardFilter filter = new InternalEndpointGuardFilter();

    @Test
    void rejectsInternalOperationsPathsWithStructuredErrorBeforeTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operations/internal/queries/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"errorType\":\"not_found\"");
        verify(chain, never()).doFilter(any(), any()); // never reaches routing
    }

    @Test
    void rejectsDeeplyNestedInternalPathsAndTheBarePrefix() throws Exception {
        for (String path : new String[] {"/operations/internal", "/operations/internal/", "/operations/internal/queries/abc/dispatch"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", path), response, mock(FilterChain.class));
            assertThat(response.getStatus()).as(path).isEqualTo(404);
        }
    }

    @Test
    void rejectsTopLevelInternalPathsAtAnyDepth() throws Exception {
        for (String path : new String[] {"/internal", "/internal/", "/internal/search/auth", "/internal/a/b/c"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);
            assertThat(response.getStatus()).as(path).isEqualTo(404);
            assertThat(response.getContentAsString()).as(path).contains("\"errorType\":\"not_found\"");
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void passesLookalikeAndPublicPathsThrough() throws Exception {
        for (String path : new String[] {"/hpds/auth/search/values", "/internalfoo", "/hpds/internal/x", "/operations/internalfoo"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).as(path).isEqualTo(200);
        }
    }

    @Test
    void passesOtherOperationsPathsThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operations/configuration");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
