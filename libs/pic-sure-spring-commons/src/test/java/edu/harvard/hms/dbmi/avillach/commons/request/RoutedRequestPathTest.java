package edu.harvard.hms.dbmi.avillach.commons.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class RoutedRequestPathTest {

    @ParameterizedTest
    @CsvSource(
        {"/mcp, /mcp", "/%6Dcp, /mcp", "/%6D%63%70, /mcp", "/%68pds/auth/query, /hpds/auth/query", "/100%25, /100%", "/%256Dcp, /%6Dcp",
            "/%25256Dcp, /%256Dcp", "/mcp%2F, /mcp/", "/mcp;x=y, /mcp", "//mcp, //mcp"}
    )
    void decodesEachSegmentExactlyOnceTheWayPathPatternsMatchIt(String rawUri, String expected) {
        assertThat(RoutedRequestPath.of(new MockHttpServletRequest("POST", rawUri))).isEqualTo(expected);
    }

    @Test
    void anInvalidPercentEncodingFallsBackToTheRawUri() {
        assertThat(RoutedRequestPath.of(new MockHttpServletRequest("POST", "/%zzcp"))).isEqualTo("/%zzcp");
        assertThat(RoutedRequestPath.of(new MockHttpServletRequest("POST", "/mcp%"))).isEqualTo("/mcp%");
    }

    @Test
    void theContextPathIsRemovedAsTheRouterRemovesIt() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/%6Dcp");
        request.setContextPath("/api");

        assertThat(RoutedRequestPath.of(request)).isEqualTo("/mcp");
    }

    @Test
    void aRequestWithoutAUriHasNoPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", null);

        assertThat(RoutedRequestPath.of(request)).isNull();
    }
}
