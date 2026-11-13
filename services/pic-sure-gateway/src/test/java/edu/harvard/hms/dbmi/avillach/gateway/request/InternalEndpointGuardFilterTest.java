package edu.harvard.hms.dbmi.avillach.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * {@link InternalEndpointGuardFilter} runs before routing/auth: {@code /operations/internal/**}, {@code /dictionary/internal/**} and
 * {@code /internal/**} are service-to-service only and must never be reachable through the public gateway, even with a stolen internal
 * token.
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
    void rejectsDictionaryInternalPathsAtAnyDepth() throws Exception {
        for (
            String path : new String[] {"/dictionary/internal", "/dictionary/internal/", "/dictionary/internal/concepts/export-metadata",
                "/dictionary/internal/a/b/c"}
        ) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);
            assertThat(response.getStatus()).as(path).isEqualTo(404);
            assertThat(response.getContentAsString()).as(path).contains("\"errorType\":\"not_found\"");
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @ParameterizedTest(name = "{0} is guarded")
    @ValueSource(
        strings = {"/dictionary/%69nternal/concepts/export-metadata", "/dictionary/interna%6C/concepts/export-metadata",
            "/dictionary/interna%6c", "/%64ictionary/internal/concepts/export-metadata", "/%64%69ctionary/%69nternal",
            "/dictionary/%2569nternal/concepts/export-metadata", "/dictionary/internal;x/concepts/export-metadata",
            "/dictionary;x/internal", "/dictionary//internal/concepts", "/dictionary/./internal/concepts",
            "/dictionary/concepts/../internal/concepts", "/dictionary%2Finternal/concepts", "/operations/%69nternal/queries/x",
            "/operations/interna%6C/queries/x", "/%6Fperations/internal/queries/x", "/%6fperations/internal",
            "/operations/%2569nternal/queries/x", "/%69nternal/search/auth", "/interna%6C", "/%2569nternal/search/auth",
            "/dictionary/concepts/%zz", "/dictionary/concepts/100%25", "/dictionary/internal/../concepts/detail"}
    )
    void rejectsEncodedAndUnnormalisedFormsOfEveryGuardedPrefix(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);

        assertThat(response.getStatus()).as(path).isEqualTo(404);
        assertThat(response.getContentAsString()).as(path).contains("\"errorType\":\"not_found\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest(name = "{0} passes")
    @ValueSource(
        strings = {"/dictionary/%69nternalized", "/dictionary/INTERNAL/concepts", "/Dictionary/internal/concepts",
            "/dictionary/concepts/%64etail", "/hpds/%69nternal/x", "/"}
    )
    void passesEncodedPathsThatDecodeOutsideEveryGuardedPrefix(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).as(path).isEqualTo(200);
    }

    @Test
    void passesPublicDictionaryPathsThrough() throws Exception {
        for (
            String path : new String[] {"/dictionary/internalized", "/dictionary/concepts/detail", "/dictionary/concepts/internal",
                "/dictionary"}
        ) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);
            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).as(path).isEqualTo(200);
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
