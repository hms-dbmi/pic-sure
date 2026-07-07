package edu.harvard.dbmi.avillach.dump.local;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

class DumpControllerAuditEventTest {

    private void assertAuditEvent(Class<?> controller, String methodName, Class<?>[] params, String expectedType, String expectedAction)
        throws Exception {
        Method method = controller.getMethod(methodName, params);
        AuditEvent event = method.getAnnotation(AuditEvent.class);
        assertNotNull(event, controller.getSimpleName() + "." + methodName + " missing @AuditEvent");
        assertEquals(expectedType, event.type(), controller.getSimpleName() + "." + methodName + " wrong type");
        assertEquals(expectedAction, event.action(), controller.getSimpleName() + "." + methodName + " wrong action");
    }

    @Test
    void dumpController() throws Exception {
        Class<?> c = DumpController.class;
        // dumpTable(DumpTable table)
        assertAuditEvent(c, "dumpTable", new Class[] {DumpTable.class}, "DATA_ACCESS", "dump.table");
        // getLastUpdated()
        assertAuditEvent(c, "getLastUpdated", new Class[] {}, "OTHER", "dump.last_updated");
        // getDBVersion()
        assertAuditEvent(c, "getDBVersion", new Class[] {}, "OTHER", "dump.db_version");
    }
}
