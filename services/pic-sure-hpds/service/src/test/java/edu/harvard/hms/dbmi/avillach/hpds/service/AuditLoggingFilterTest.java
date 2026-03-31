package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.SessionIdResolver;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditAttributes;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditLoggingFilter;

class AuditLoggingFilterTest {

    private AuditLoggingFilter filter;
    private LoggingClient loggingClient;

    @BeforeEach
    void setup() {
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        filter = new AuditLoggingFilter(loggingClient, null, null);
    }

    private MockHttpServletRequest mockRequest(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.168.1.1");
        request.setLocalAddr("10.0.0.1");
        request.setLocalPort(8080);
        return request;
    }

    // ---- Reads event type/action from request attributes (set by AuditInterceptor) ----

    @Test
    void testReadsEventTypeAndActionFromAttributes() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "QUERY");
        request.setAttribute(AuditAttributes.ACTION, "query.submitted");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.submitted", event.getAction());
    }

    @Test
    void testFallsBackToOtherAndMethodWhenNoAttributes() throws Exception {
        MockHttpServletRequest request = mockRequest("/unknown/path", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("OTHER", event.getEventType());
        assertEquals("GET", event.getAction());
    }

    @Test
    void testDataAccessEventType() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query/abc/result", "POST");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "DATA_ACCESS");
        request.setAttribute(AuditAttributes.ACTION, "query.result");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("DATA_ACCESS", captor.getValue().getEventType());
        assertEquals("query.result", captor.getValue().getAction());
    }

    // ---- API version detection ----

    @Test
    void testV3PathSetsApiVersion() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/v3/query", "POST");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "QUERY");
        request.setAttribute(AuditAttributes.ACTION, "query.submitted");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("v3", captor.getValue().getMetadata().get("api_version"));
    }

    @Test
    void testNonV3PathHasNoApiVersion() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "QUERY");
        request.setAttribute(AuditAttributes.ACTION, "query.submitted");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertNull(captor.getValue().getMetadata().get("api_version"));
    }

    // ---- IP extraction ----

    @Test
    void testXForwardedForSingleIpUsedAsSrcIp() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.addHeader("X-Forwarded-For", "203.0.113.50");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("203.0.113.50", captor.getValue().getRequest().getSrcIp());
    }

    @Test
    void testXForwardedForMultipleIpsUsesFirst() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18, 150.172.238.178");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("203.0.113.50", captor.getValue().getRequest().getSrcIp());
    }

    @Test
    void testNoXForwardedForFallsBackToRemoteAddr() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("192.168.1.1", captor.getValue().getRequest().getSrcIp());
    }

    // ---- Session ID ----

    @Test
    void testXSessionIdHeaderUsedWhenPresent() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.addHeader("X-Session-Id", "my-session-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("my-session-123", captor.getValue().getSessionId());
    }

    @Test
    void testNoXSessionIdGeneratesHashFromIpAndUserAgent() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.addHeader("User-Agent", "TestBrowser/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());

        String expectedHash = SessionIdResolver.resolve(null, "192.168.1.1", "TestBrowser/1.0");
        assertEquals(expectedHash, captor.getValue().getSessionId());
    }

    // ---- Error detection ----

    @Test
    void testStatus200HasNoErrorMap() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertNull(captor.getValue().getError());
    }

    @Test
    void testStatus404HasClientErrorMap() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        Map<String, Object> error = captor.getValue().getError();
        assertNotNull(error);
        assertEquals(404, error.get("status"));
        assertEquals("client_error", error.get("error_type"));
    }

    @Test
    void testStatus500HasServerErrorMap() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        Map<String, Object> error = captor.getValue().getError();
        assertNotNull(error);
        assertEquals(500, error.get("status"));
        assertEquals("server_error", error.get("error_type"));
    }

    // ---- Bearer token passthrough ----

    @Test
    void testAuthorizationHeaderPresentCallsSendWithAuthAndRequestId() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.addHeader("Authorization", "Bearer token123");
        request.addHeader("X-Request-Id", "req-456");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture(), eq("Bearer token123"), eq("req-456"));
        verify(loggingClient, never()).send(any(LoggingEvent.class));
    }

    @Test
    void testNoAuthHeaderAndNoRequestIdCallsSendWithoutExtra() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        verify(loggingClient, never()).send(any(LoggingEvent.class), anyString(), anyString());
    }

    // ---- Disabled logging client ----

    @Test
    void testDisabledLoggingClientDoesNotSend() throws Exception {
        when(loggingClient.isEnabled()).thenReturn(false);

        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        verify(loggingClient, never()).send(any(LoggingEvent.class));
        verify(loggingClient, never()).send(any(LoggingEvent.class), anyString(), anyString());
    }

    // ---- AuditAttributes metadata merge ----

    @Test
    void testMergesControllerMetadata() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        // Simulate what a controller would do
        AuditAttributes.putMetadata(request, "query_id", "def-456");
        AuditAttributes.putMetadata(request, "result_type", "COUNT");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("def-456", event.getMetadata().get("query_id"));
        assertEquals("COUNT", event.getMetadata().get("result_type"));
        // Session ID is a top-level field
        assertNotNull(event.getSessionId());
    }

    @Test
    void testSessionIdFromHeaderTakesPrecedence() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.addHeader("X-Session-Id", "filter-session");
        // Controller tries to set session_id in metadata — filter's top-level field takes precedence
        AuditAttributes.putMetadata(request, "session_id", "controller-session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("filter-session", captor.getValue().getSessionId());
    }

    // ---- RequestInfo fields ----

    @Test
    void testRequestInfoPopulatedCorrectly() throws Exception {
        MockHttpServletRequest request = mockRequest("/PIC-SURE/query", "POST");
        request.addHeader("User-Agent", "TestAgent/2.0");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.setContentType("application/json");

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();

        assertEquals("POST", event.getRequest().getMethod());
        assertEquals("/PIC-SURE/query", event.getRequest().getUrl());
        assertEquals("192.168.1.1", event.getRequest().getSrcIp());
        assertEquals("10.0.0.1", event.getRequest().getDestIp());
        assertEquals(Integer.valueOf(8080), event.getRequest().getDestPort());
        assertEquals("TestAgent/2.0", event.getRequest().getHttpUserAgent());
        assertEquals(Integer.valueOf(200), event.getRequest().getStatus());
        assertEquals("application/json", event.getRequest().getHttpContentType());
        assertNotNull(event.getRequest().getDuration());
    }

}
