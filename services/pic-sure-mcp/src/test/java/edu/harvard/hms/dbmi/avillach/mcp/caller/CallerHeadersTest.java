package edu.harvard.hms.dbmi.avillach.mcp.caller;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.List;
import java.util.Map;

/** Covers reading caller headers off an MCP request, replaying them, redacting them, and binding the request ID to the MDC. */
class CallerHeadersTest {

    private static final String BEARER = "Bearer secret-bearer-value";
    private static final String API_KEY = "secret-api-key-value";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void extractorPicksUpAllFourHeaders() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp");
        servletRequest.addHeader("Authorization", BEARER);
        servletRequest.addHeader("X-PICSURE-API-Key", API_KEY);
        servletRequest.addHeader("X-Request-Id", "req-123");
        servletRequest.addHeader("X-Forwarded-For", "203.0.113.7");
        servletRequest.addHeader("X-Other", "ignored");

        McpTransportContext context = CallerHeaders.extract(ServerRequest.create(servletRequest, List.of()));

        assertThat(CallerHeaders.from(context)).isEqualTo(new CallerHeaders(BEARER, API_KEY, "req-123", "203.0.113.7"));
    }

    @Test
    void extractorLeavesMissingHeadersNull() {
        McpTransportContext context = CallerHeaders.extract(ServerRequest.create(new MockHttpServletRequest("POST", "/mcp"), List.of()));

        assertThat(CallerHeaders.from(context)).isEqualTo(new CallerHeaders(null, null, null, null));
    }

    @Test
    void fromReturnsEmptyHeadersWithoutAnExtractedRecord() {
        assertThat(CallerHeaders.from(null)).isEqualTo(new CallerHeaders(null, null, null, null));
        assertThat(CallerHeaders.from(McpTransportContext.create(Map.of()))).isEqualTo(new CallerHeaders(null, null, null, null));
    }

    @Test
    void extractorJoinsEveryForwardedForValue() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp");
        servletRequest.addHeader("X-Forwarded-For", "203.0.113.7");
        servletRequest.addHeader("X-Forwarded-For", "198.51.100.2, 10.0.0.1");

        McpTransportContext context = CallerHeaders.extract(ServerRequest.create(servletRequest, List.of()));

        assertThat(CallerHeaders.from(context).forwardedFor()).isEqualTo("203.0.113.7, 198.51.100.2, 10.0.0.1");
    }

    @Test
    void lineBreaksAreStrippedFromRequestIdAndForwardedFor() {
        CallerHeaders headers = new CallerHeaders(null, null, "req-1\r\nforged: line", "203.0.113.7\n10.0.0.1");

        assertThat(headers.requestId()).isEqualTo("req-1forged: line");
        assertThat(headers.forwardedFor()).isEqualTo("203.0.113.710.0.0.1");
        assertThat(headers.toString()).doesNotContain("\r", "\n");
    }

    @Test
    void applyToReplaysTheFourCallerHeaders() {
        HttpHeaders headers = new HttpHeaders();

        new CallerHeaders(BEARER, API_KEY, "req-123", "203.0.113.7").applyTo(headers);

        assertThat(headers.getFirst("Authorization")).isEqualTo(BEARER);
        assertThat(headers.getFirst("X-PICSURE-API-Key")).isEqualTo(API_KEY);
        assertThat(headers.getFirst("X-Request-Id")).isEqualTo("req-123");
        assertThat(headers.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.7");
        assertThat(headers.keySet()).hasSize(4).doesNotContain("X-PIC-SURE-MCP-TOKEN");
    }

    @Test
    void applyToSkipsHeadersTheCallerDidNotSend() {
        HttpHeaders headers = new HttpHeaders();

        new CallerHeaders(null, null, null, null).applyTo(headers);

        assertThat(headers.keySet()).isEmpty();
    }

    @Test
    void toStringRedactsTheBearerTokenAndApiKey() {
        String text = new CallerHeaders(BEARER, API_KEY, "req-123", "203.0.113.7").toString();

        assertThat(text).doesNotContain("secret-bearer-value", "Bearer", API_KEY).contains("req-123", "203.0.113.7", "<redacted>");
    }

    @Test
    void bindRequestIdSetsAndRestoresTheMdc() {
        MDC.put(CallerHeaders.MDC_KEY, "outer");

        try (CallerHeaders.MdcScope ignored = new CallerHeaders(null, null, "req-123", null).bindRequestId()) {
            assertThat(MDC.get(CallerHeaders.MDC_KEY)).isEqualTo("req-123");
        }

        assertThat(MDC.get(CallerHeaders.MDC_KEY)).isEqualTo("outer");
    }

    @Test
    void bindRequestIdRemovesTheKeyItAdded() {
        try (CallerHeaders.MdcScope ignored = new CallerHeaders(null, null, "req-123", null).bindRequestId()) {
            assertThat(MDC.get(CallerHeaders.MDC_KEY)).isEqualTo("req-123");
        }

        assertThat(MDC.get(CallerHeaders.MDC_KEY)).isNull();
    }

    @Test
    void bindRequestIdLeavesTheMdcUnsetWithoutARequestId() {
        try (CallerHeaders.MdcScope ignored = new CallerHeaders(null, null, null, null).bindRequestId()) {
            assertThat(MDC.get(CallerHeaders.MDC_KEY)).isNull();
        }
    }
}
