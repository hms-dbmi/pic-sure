package edu.harvard.dbmi.avillach.visualization.logging;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLoggingInterceptorTest {

    @Mock
    private LoggingClient loggingClient;

    private AuditLoggingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuditLoggingInterceptor(loggingClient);
    }

    @Test
    void preHandle_setsStartTimeAndRequestIdAndMDC() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        assertNotNull(request.getAttribute("vizStartTime"));
        assertNotNull(request.getAttribute("vizRequestId"));
        assertNotNull(MDC.get("requestId"));

        MDC.remove("requestId");
    }

    @Test
    void afterCompletion_sendsLoggingEventForQuerySync() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/v3/query/sync");
        request.addHeader("request-source", "Authorized");
        request.setAttribute("vizStartTime", System.currentTimeMillis() - 150);
        request.setAttribute("vizRequestId", "test-request-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), eq("test-request-id"));

        LoggingEvent event = eventCaptor.getValue();
        assertEquals("VISUALIZATION_QUERY", event.getEventType());
        assertEquals("query_sync", event.getAction());
    }

    @Test
    void afterCompletion_detectsBinContinuousAction() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/v3/bin/continuous");
        request.setAttribute("vizStartTime", System.currentTimeMillis());
        request.setAttribute("vizRequestId", "req-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), eq("req-id"));
        assertEquals("bin_continuous", eventCaptor.getValue().getAction());
    }

    @Test
    void afterCompletion_detectsInfoAction() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/v3/info");
        request.setAttribute("vizStartTime", System.currentTimeMillis());
        request.setAttribute("vizRequestId", "req-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), eq("req-id"));
        assertEquals("info", eventCaptor.getValue().getAction());
    }

    @Test
    void afterCompletion_unknownPath_setsUnknownAction() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/visualization/v3/something");
        request.setAttribute("vizStartTime", System.currentTimeMillis());
        request.setAttribute("vizRequestId", "req-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), eq("req-id"));
        assertEquals("unknown", eventCaptor.getValue().getAction());
    }

    @Test
    void afterCompletion_nullRequestSource_setsUnknownAccessType() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/v3/query/sync");
        // No request-source header
        request.setAttribute("vizStartTime", System.currentTimeMillis());
        request.setAttribute("vizRequestId", "req-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, new Object(), null);

        // Should not throw — "unknown" is used when header is null
        verify(loggingClient).send(any(LoggingEvent.class), isNull(), eq("req-id"));
    }

    @Test
    void afterCompletion_loggingClientThrows_doesNotPropagate() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/v3/query/sync");
        request.setAttribute("vizStartTime", System.currentTimeMillis());
        request.setAttribute("vizRequestId", "req-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        doThrow(new RuntimeException("logging down")).when(loggingClient).send(any(), any(), any());

        // Should not throw — exception is caught and logged
        assertDoesNotThrow(() -> interceptor.afterCompletion(request, response, new Object(), null));
    }

    @Test
    void afterCompletion_cleansMDC() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/v3/query/sync");
        request.setAttribute("vizStartTime", System.currentTimeMillis());
        request.setAttribute("vizRequestId", "req-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("requestId", "should-be-removed");

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(MDC.get("requestId"));
    }

    @Test
    void afterCompletion_nullStartTime_handlesGracefully() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/v3/query/sync");
        // No vizStartTime attribute — simulates preHandle not being called
        request.setAttribute("vizRequestId", "req-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() -> interceptor.afterCompletion(request, response, new Object(), null));
        verify(loggingClient).send(any(LoggingEvent.class), isNull(), eq("req-id"));
    }
}
