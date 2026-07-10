package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;

class BodyMutationFilterTest {

    @Test
    void swapsTopLevelQueryWhenAttributePresent() throws Exception {
        // BufferedRequestWrapper delegates setAttribute/getAttribute to the wrapped request, so it must be
        // backed by a real attribute store (MockHttpServletRequest) rather than a bare Mockito mock, which
        // would silently drop the attribute set below and make this security-critical test pass vacuously.
        BufferedRequestWrapper req =
            new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"query\":\"original\",\"resourceUUID\":\"r-1\"}".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, "{\"_topmed_consents\":[\"phs1\"]}");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(new ObjectMapper()).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        String newBody = new String(req.getBody());
        assertThat(newBody).contains("_topmed_consents");
        assertThat(newBody).contains("\"resourceUUID\":\"r-1\"");
    }

    @Test
    void noAttributeLeavesBodyUntouched() throws Exception {
        BufferedRequestWrapper req = new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"query\":\"original\"}".getBytes());
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(new ObjectMapper()).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(new String(req.getBody())).isEqualTo("{\"query\":\"original\"}");
    }

    @Test
    void wrapsMutatedQueryWhenOriginalBodyIsNotJson() throws Exception {
        BufferedRequestWrapper req = new BufferedRequestWrapper(new MockHttpServletRequest(), "not json at all".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, "{\"_topmed_consents\":[\"phs1\"]}");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(new ObjectMapper()).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        String newBody = new String(req.getBody());
        assertThat(newBody).contains("_topmed_consents").contains("\"query\":");
    }

    @Test
    void storesNonJsonMutatedQueryAsTextNode() throws Exception {
        BufferedRequestWrapper req =
            new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"query\":\"original\",\"resourceUUID\":\"r-1\"}".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, "not json at all");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(new ObjectMapper()).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        String newBody = new String(req.getBody());
        assertThat(newBody).isEqualTo("{\"query\":\"not json at all\",\"resourceUUID\":\"r-1\"}");
    }

    @Test
    void wrapsMutatedQueryWhenOriginalBodyIsEmpty() throws Exception {
        BufferedRequestWrapper req = new BufferedRequestWrapper(new MockHttpServletRequest(), new byte[0]);
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, "{\"_topmed_consents\":[\"phs1\"]}");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(new ObjectMapper()).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        String newBody = new String(req.getBody());
        assertThat(newBody).isEqualTo("{\"query\":{\"_topmed_consents\":[\"phs1\"]}}");
    }

    @Test
    void failsClosedAndSkipsChainWhenMutationRequiredButBuildingBodyThrows() throws Exception {
        // Force the guarded failure inside mergeQueryIntoBody: writeValueAsBytes is the only call in that
        // method not already wrapped in a try/catch that swallows IOException, so spying on it lets us
        // simulate a future fallible step throwing without changing production code.
        ObjectMapper mapper = spy(new ObjectMapper());
        doThrow(new JsonProcessingException("boom") {}).when(mapper).writeValueAsBytes(any());

        BufferedRequestWrapper req = new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"query\":\"original\"}".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, "{\"_topmed_consents\":[\"phs1\"]}");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(mapper).doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(resp.getContentAsString()).contains("body_mutation_failed");
        // The original, un-swapped body must never be forwarded.
        assertThat(new String(req.getBody())).isEqualTo("{\"query\":\"original\"}");
    }
}
