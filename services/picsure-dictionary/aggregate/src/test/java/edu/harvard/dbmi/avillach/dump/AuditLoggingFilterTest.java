package edu.harvard.dbmi.avillach.dump;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;

class AuditLoggingFilterTest {

    private AuditLoggingFilter filter;
    private LoggingClient loggingClient;

    @BeforeEach
    void setup() {
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        filter = new AuditLoggingFilter(loggingClient, null, null);
    }

    private MockHttpServletRequest mockRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dump");
        request.setRemoteAddr("192.168.1.1");
        request.setLocalAddr("10.0.0.1");
        request.setLocalPort(8080);
        return request;
    }

    @Test
    void xClientTypeHeaderLandsAsCaller() throws Exception {
        MockHttpServletRequest request = mockRequest();
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("PYTHON_ADAPTER", captor.getValue().getCaller());
    }

    @Test
    void callerIsUnsetWithoutTheXClientTypeHeader() throws Exception {
        MockHttpServletRequest request = mockRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertNull(captor.getValue().getCaller());
    }

    @Test
    void callerIsUnsetWhenTheXClientTypeHeaderIsEmpty() throws Exception {
        MockHttpServletRequest request = mockRequest();
        request.addHeader("X-Client-Type", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertNull(captor.getValue().getCaller());
    }
}
