package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditInterceptor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditLoggingFilter;

class AuditIntegrationTest {

    private AuditInterceptor interceptor;
    private LoggingClient loggingClient;
    private AuditLoggingFilter filter;

    // Test controller with @AuditEvent annotation
    static class TestController {

        @AuditEvent(type = "QUERY", action = "query.submitted")
        public void queryEndpoint() {}

        @AuditEvent(type = "DATA_ACCESS", action = "query.result")
        public void resultEndpoint() {}
    }

    @BeforeEach
    void setup() {
        interceptor = new AuditInterceptor();
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        filter = new AuditLoggingFilter(loggingClient, null, null);
    }

    @Test
    void happyPathFullChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/PIC-SURE/v3/query");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("User-Agent", "test-agent");
        request.addHeader("Authorization", "Bearer abc123");
        request.addHeader("X-Request-Id", "req-42");
        request.addHeader("X-Session-Id", "session-99");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // Step 1: Interceptor reads @AuditEvent and sets request attributes
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), "queryEndpoint");
        interceptor.preHandle(request, response, handlerMethod);

        // Step 2: Controller sets domain metadata
        AuditAttributes.putMetadata(request, "query_id", "q-abc");
        AuditAttributes.putMetadata(request, "result_type", "COUNT");

        // Step 3: Filter runs and sends LoggingEvent
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);

        // Step 4: Capture and verify the LoggingEvent
        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        ArgumentCaptor<String> bearerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(loggingClient).send(eventCaptor.capture(), bearerCaptor.capture(), requestIdCaptor.capture());

        LoggingEvent event = eventCaptor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.submitted", event.getAction());
        assertNull(event.getError());

        // Verify request info
        assertNotNull(event.getRequest());
        assertEquals("POST", event.getRequest().getMethod());
        assertEquals("/PIC-SURE/v3/query", event.getRequest().getUrl());
        assertEquals("10.0.0.1", event.getRequest().getSrcIp());
        assertEquals(200, event.getRequest().getStatus());

        // Verify session ID (top-level on event, not in metadata)
        assertEquals("session-99", event.getSessionId());

        // Verify metadata
        Map<String, Object> metadata = event.getMetadata();
        assertNotNull(metadata);
        assertEquals("v3", metadata.get("api_version"));
        assertEquals("q-abc", metadata.get("query_id"));
        assertEquals("COUNT", metadata.get("result_type"));

        // Verify bearer token and request ID passthrough
        assertEquals("Bearer abc123", bearerCaptor.getValue());
        assertEquals("req-42", requestIdCaptor.getValue());

        // Verify the filter chain was invoked
        verify(chain).doFilter(request, response);
    }

    @Test
    void v3PathDetection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/PIC-SURE/v3/search/values/");
        request.setRemoteAddr("192.168.1.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // Interceptor with DATA_ACCESS annotation
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), "resultEndpoint");
        interceptor.preHandle(request, response, handlerMethod);

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture());

        LoggingEvent event = eventCaptor.getValue();
        assertEquals("DATA_ACCESS", event.getEventType());
        assertEquals("query.result", event.getAction());
        assertEquals("v3", event.getMetadata().get("api_version"));
    }

    @Test
    void errorResponseIncludesErrorMap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/PIC-SURE/query");
        request.setRemoteAddr("10.0.0.5");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        // Interceptor sets annotation attributes
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), "queryEndpoint");
        interceptor.preHandle(request, response, handlerMethod);

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture());

        LoggingEvent event = eventCaptor.getValue();
        assertEquals("QUERY", event.getEventType());

        // Verify error map is populated for 5xx
        Map<String, Object> error = event.getError();
        assertNotNull(error, "Error map should be present for 500 status");
        assertEquals(500, error.get("status"));
        assertEquals("server_error", error.get("error_type"));

        // Verify no v3 api_version for non-v3 path
        assertNull(event.getMetadata().get("api_version"));
    }
}
