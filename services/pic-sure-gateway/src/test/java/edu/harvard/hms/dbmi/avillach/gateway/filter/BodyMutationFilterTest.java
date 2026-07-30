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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;

class BodyMutationFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode node(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    @Test
    void replacesTheWholeBodyWithTheMutatedQuery() throws Exception {
        // BufferedRequestWrapper delegates setAttribute/getAttribute to the wrapped request, so it must be
        // backed by a real attribute store (MockHttpServletRequest) rather than a bare Mockito mock, which
        // would silently drop the attribute set below and make this security-critical test pass vacuously.
        BufferedRequestWrapper req = new BufferedRequestWrapper(
            new MockHttpServletRequest(), "{\"expectedResultType\":\"COUNT\",\"select\":[\"\\\\a\\\\\"]}".getBytes()
        );
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, node("{\"expectedResultType\":\"COUNT\",\"_topmed_consents\":[\"phs1\"]}"));
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(MAPPER).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        // The outbound body IS the mutated query: no envelope, no leftover fields from the submitted body.
        assertThat(new String(req.getBody())).isEqualTo("{\"expectedResultType\":\"COUNT\",\"_topmed_consents\":[\"phs1\"]}");
    }

    @Test
    void doesNotWrapTheMutatedQueryInALegacyQueryEnvelope() throws Exception {
        // Regression guard for the deleted root.set("query", ...) splice: a bare v3 Query body must stay bare.
        BufferedRequestWrapper req =
            new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"expectedResultType\":\"COUNT\"}".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, node("{\"_topmed_consents\":[\"phs1\"]}"));

        new BodyMutationFilter(MAPPER).doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(new String(req.getBody())).doesNotContain("\"query\"");
    }

    @Test
    void noAttributeLeavesBodyUntouched() throws Exception {
        BufferedRequestWrapper req =
            new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"expectedResultType\":\"COUNT\"}".getBytes());
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(MAPPER).doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(new String(req.getBody())).isEqualTo("{\"expectedResultType\":\"COUNT\"}");
    }

    @Test
    void replacesANonJsonOriginalBodyEntirely() throws Exception {
        // A non-JSON original body must not abort the swap -- that would forward the un-swapped, potentially
        // unauthorized body. Wholesale replacement makes the original body's shape irrelevant.
        BufferedRequestWrapper req = new BufferedRequestWrapper(new MockHttpServletRequest(), "not json at all".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, node("{\"_topmed_consents\":[\"phs1\"]}"));
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(MAPPER).doFilter(req, mock(HttpServletResponse.class), chain);

        verify(chain).doFilter(any(), any());
        assertThat(new String(req.getBody())).isEqualTo("{\"_topmed_consents\":[\"phs1\"]}");
    }

    @Test
    void replacesAnEmptyOriginalBody() throws Exception {
        // result/signed-url paths are bodyless: the query PSAMA authorized came from the dispatch lookup.
        BufferedRequestWrapper req = new BufferedRequestWrapper(new MockHttpServletRequest(), new byte[0]);
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, node("{\"_topmed_consents\":[\"phs1\"]}"));

        new BodyMutationFilter(MAPPER).doFilter(req, mock(HttpServletResponse.class), mock(FilterChain.class));

        assertThat(new String(req.getBody())).isEqualTo("{\"_topmed_consents\":[\"phs1\"]}");
    }

    @Test
    void failsClosedWhenTheMutatedQueryIsNotAJsonObject() throws Exception {
        // Defence in depth: PsamaIntrospectionFilter only stashes object nodes, but a scalar reaching here
        // would be written out as a bare JSON string -- an unrunnable body, and worse, one that silently
        // discards the consent filtering. Reject rather than forward anything.
        BufferedRequestWrapper req =
            new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"expectedResultType\":\"COUNT\"}".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, MAPPER.getNodeFactory().textNode("{\"_topmed_consents\":[\"phs1\"]}"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(MAPPER).doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(resp.getContentAsString()).contains("body_mutation_failed");
        assertThat(new String(req.getBody())).isEqualTo("{\"expectedResultType\":\"COUNT\"}");
    }

    @Test
    void failsClosedAndSkipsChainWhenMutationRequiredButBuildingBodyThrows() throws Exception {
        // Force the guarded failure inside the swap: writeValueAsBytes is the fallible step, so spying on it
        // simulates a serialization failure without changing production code.
        ObjectMapper mapper = spy(new ObjectMapper());
        doThrow(new JsonProcessingException("boom") {}).when(mapper).writeValueAsBytes(any());

        BufferedRequestWrapper req =
            new BufferedRequestWrapper(new MockHttpServletRequest(), "{\"expectedResultType\":\"COUNT\"}".getBytes());
        req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, node("{\"_topmed_consents\":[\"phs1\"]}"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new BodyMutationFilter(mapper).doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(resp.getContentAsString()).contains("body_mutation_failed");
        // The original, un-swapped body must never be forwarded.
        assertThat(new String(req.getBody())).isEqualTo("{\"expectedResultType\":\"COUNT\"}");
    }
}
