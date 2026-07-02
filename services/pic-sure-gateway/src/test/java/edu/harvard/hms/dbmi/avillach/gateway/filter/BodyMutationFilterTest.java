package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
}
