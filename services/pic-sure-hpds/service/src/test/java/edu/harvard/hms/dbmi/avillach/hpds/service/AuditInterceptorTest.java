package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditInterceptor;

class AuditInterceptorTest {

    private AuditInterceptor interceptor;

    @BeforeEach
    void setup() {
        interceptor = new AuditInterceptor();
    }

    // Test controller with annotated methods
    static class TestController {

        @AuditEvent(type = "QUERY", action = "query.submitted")
        public void queryEndpoint() {
        }

        @AuditEvent(type = "DATA_ACCESS", action = "query.result")
        public void resultEndpoint() {
        }

        public void unannotatedEndpoint() {
        }
    }

    private HandlerMethod handlerFor(String methodName) throws Exception {
        return new HandlerMethod(new TestController(), methodName);
    }

    @Test
    void testSetsEventTypeAndActionFromAnnotation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, handlerFor("queryEndpoint"));

        assertTrue(result);
        assertEquals("QUERY", request.getAttribute(AuditAttributes.EVENT_TYPE));
        assertEquals("query.submitted", request.getAttribute(AuditAttributes.ACTION));
    }

    @Test
    void testDifferentAnnotationValues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, handlerFor("resultEndpoint"));

        assertEquals("DATA_ACCESS", request.getAttribute(AuditAttributes.EVENT_TYPE));
        assertEquals("query.result", request.getAttribute(AuditAttributes.ACTION));
    }

    @Test
    void testUnannotatedMethodSetsNoAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, handlerFor("unannotatedEndpoint"));

        assertTrue(result);
        assertNull(request.getAttribute(AuditAttributes.EVENT_TYPE));
        assertNull(request.getAttribute(AuditAttributes.ACTION));
    }

    @Test
    void testNonHandlerMethodObjectIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Pass a non-HandlerMethod object (e.g., a string)
        boolean result = interceptor.preHandle(request, response, "not-a-handler");

        assertTrue(result);
        assertNull(request.getAttribute(AuditAttributes.EVENT_TYPE));
    }

    @Test
    void testAlwaysReturnsTrue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handlerFor("queryEndpoint")));
        assertTrue(interceptor.preHandle(request, response, handlerFor("unannotatedEndpoint")));
        assertTrue(interceptor.preHandle(request, response, "not-a-handler"));
    }
}
