package edu.harvard.dbmi.avillach.logging;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AuditEventTest {

    static class TestController {
        @AuditEvent(type = "QUERY", action = "query.submitted")
        public void annotatedMethod() {}

        public void unannotatedMethod() {}
    }

    @Test
    void annotationRetainedAtRuntime() throws Exception {
        Method m = TestController.class.getMethod("annotatedMethod");
        AuditEvent event = m.getAnnotation(AuditEvent.class);
        assertNotNull(event);
        assertEquals("QUERY", event.type());
        assertEquals("query.submitted", event.action());
    }

    @Test
    void unannotatedMethodReturnsNull() throws Exception {
        Method m = TestController.class.getMethod("unannotatedMethod");
        assertNull(m.getAnnotation(AuditEvent.class));
    }
}
