package edu.harvard.dbmi.avillach.visualization.controller;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.dbmi.avillach.visualization.model.ContinuousBinningRequest;
import edu.harvard.dbmi.avillach.visualization.model.DistributionRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the audit label on each handler of the three visualization controllers, and checks that every request-mapped method declared on
 * those controllers carries {@code @AuditEvent}. A controller added outside {@code CONTROLLERS} is not checked.
 */
class ControllerAuditEventTest {

    private static final List<Class<?>> CONTROLLERS =
        List.of(BinningController.class, DistributionController.class, PicsureMetadataController.class);

    private static void assertAuditEvent(
        Class<?> controller, String methodName, Class<?>[] params, String expectedType, String expectedAction
    ) throws Exception {
        Method method = controller.getMethod(methodName, params);
        AuditEvent event = method.getAnnotation(AuditEvent.class);
        assertNotNull(event, controller.getSimpleName() + "." + methodName + " missing @AuditEvent");
        assertEquals(expectedType, event.type(), controller.getSimpleName() + "." + methodName + " wrong type");
        assertEquals(expectedAction, event.action(), controller.getSimpleName() + "." + methodName + " wrong action");
    }

    @Test
    void everyHandlerCarriesAuditEvent() {
        List<String> unlabelled = CONTROLLERS.stream().flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
            .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
            .filter(method -> !method.isAnnotationPresent(AuditEvent.class))
            .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName()).toList();

        assertTrue(unlabelled.isEmpty(), "handlers missing @AuditEvent: " + unlabelled);
    }

    @Test
    void binningController() throws Exception {
        assertAuditEvent(
            BinningController.class, "binContinuous", new Class[] {ContinuousBinningRequest.class, HttpServletRequest.class}, "QUERY",
            "visualization.bin_continuous"
        );
    }

    @Test
    void distributionController() throws Exception {
        assertAuditEvent(
            DistributionController.class, "distributions",
            new Class[] {String.class, DistributionRequest.class, String.class, HttpServletRequest.class}, "QUERY",
            "visualization.distributions"
        );
    }

    @Test
    void picsureMetadataController() throws Exception {
        assertAuditEvent(PicsureMetadataController.class, "queryFormat", new Class[] {}, "OTHER", "query.format");
    }
}
