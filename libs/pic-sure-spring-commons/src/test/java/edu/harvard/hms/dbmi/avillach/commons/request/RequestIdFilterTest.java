package edu.harvard.hms.dbmi.avillach.commons.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesAndEchoesARequestIdWhenNoneIsSupplied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void propagatesAnExistingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query");
        request.addHeader(RequestIdFilter.HEADER, "given-request-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("given-request-id");
    }

    @Test
    void mdcCarriesTheRequestIdWhileTheChainExecutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query");
        request.addHeader(RequestIdFilter.HEADER, "mdc-check-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("mdc-check-id"));
    }
}
