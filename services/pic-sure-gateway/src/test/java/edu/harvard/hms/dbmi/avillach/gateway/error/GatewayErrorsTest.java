package edu.harvard.hms.dbmi.avillach.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayErrorsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesStatusContentTypeAndEscapedBodyWithNullRequestIdWhenMdcEmpty() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        GatewayErrors
            .write(resp, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_BODY_TOO_LARGE", "Request body exceeds the \"max\" size \\ allowed.");

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(resp.getContentType()).isEqualTo("application/json");
        assertThat(resp.getContentAsString()).contains("\"errorType\":\"REQUEST_BODY_TOO_LARGE\"")
            .contains("\"message\":\"Request body exceeds the \\\"max\\\" size \\\\ allowed.\"").contains("\"requestId\":null");
    }

    @Test
    void includesQuotedRequestIdWhenMdcSet() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MDC.put("requestId", "r-1");

        GatewayErrors.write(resp, HttpStatus.BAD_REQUEST, "SOME_ERROR", "plain message");

        assertThat(resp.getContentAsString()).contains("\"requestId\":\"r-1\"");
    }
}
