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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLoggingFilterTest {

    @Mock
    private LoggingClient loggingClient;

    private AuditLoggingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuditLoggingFilter(loggingClient);
    }

    @Test
    void doFilter_generatesRequestIdAndLogsDistributions() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/distributions");
        request.setContentType("application/json");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNotNull(response.getHeader("X-Request-Id"));
        assertNull(MDC.get("requestId"));

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), eq("Bearer token"), eq(response.getHeader("X-Request-Id")));

        LoggingEvent event = eventCaptor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("visualization.distributions", event.getAction());
        assertEquals(response.getHeader("X-Request-Id"), event.getRequest().getRequestId());
        assertEquals("POST", event.getRequest().getMethod());
        assertEquals("/distributions", event.getRequest().getUrl());
    }

    @Test
    void doFilter_preservesIncomingRequestIdAndSessionId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bin/continuous");
        request.addHeader("X-Request-Id", "incoming-request");
        request.addHeader("X-Session-Id", "session-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("incoming-request", response.getHeader("X-Request-Id"));
        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), eq("incoming-request"));
        assertEquals("visualization.bin_continuous", eventCaptor.getValue().getAction());
        assertEquals("session-1", eventCaptor.getValue().getSessionId());
    }

    @Test
    void doFilter_includesContextMetadata() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bin/continuous");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {
            AuditLoggingContext.addMetadata((MockHttpServletRequest) req, "route", "bin_continuous");
            AuditLoggingContext.addMetadata((MockHttpServletRequest) req, "output_point_count", 2);
        };

        filter.doFilter(request, response, chain);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), anyString());
        assertEquals("bin_continuous", eventCaptor.getValue().getMetadata().get("route"));
        assertEquals(2, eventCaptor.getValue().getMetadata().get("output_point_count"));
    }

    @Test
    void doFilter_skipsHealthAndCompatibilityRoutes() throws ServletException, IOException {
        filter.doFilter(new MockHttpServletRequest("POST", "/info"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("POST", "/query/format"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(loggingClient);
    }

    @Test
    void doFilter_statusFailureAddsErrorMetadata() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/distributions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> ((MockHttpServletResponse) resp).setStatus(400);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), anyString());
        Map<String, Object> error = eventCaptor.getValue().getError();
        assertEquals(400, error.get("status"));
        assertEquals("client_error", error.get("error_type"));
    }

    @Test
    void doFilter_loggingClientThrowsDoesNotPropagate() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/distributions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new RuntimeException("logging down")).when(loggingClient).send(any(), any(), any());

        assertDoesNotThrow(() -> filter.doFilter(request, response, new MockFilterChain()));
    }
}
