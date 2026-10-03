package edu.harvard.hms.dbmi.avillach.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import edu.harvard.dbmi.avillach.logging.AuditEvent;

/**
 * Pins the {@link AuditEvent} label on every request handler in this service. Controllers are found by classpath scan and handlers by their
 * {@link RequestMapping} meta-annotation, so no Spring context starts. A new handler without a label, a new handler missing from
 * {@link #EXPECTED}, or a changed label fails the build. The labels mirror the gateway's audit route table for the same paths.
 */
class ControllerAuditEventTest {

    private static final String BASE_PACKAGE = "edu.harvard.hms.dbmi.avillach.query";

    private static final Map<String, String> EXPECTED = new TreeMap<>(
        Map.ofEntries(
            Map.entry("HpdsQueryController.query", "QUERY query.submitted"),
            Map.entry("HpdsQueryController.querySync", "QUERY query.sync"),
            Map.entry("HpdsQueryController.status", "QUERY query.status"),
            Map.entry("HpdsQueryController.result", "DATA_ACCESS query.result"),
            Map.entry("HpdsQueryController.signedUrl", "DATA_ACCESS query.signed_url"),
            Map.entry("HpdsQueryController.metadata", "QUERY query.metadata"),
            Map.entry("AggregateController.querySync", "QUERY query.sync"), Map.entry("AggregateController.query", "QUERY query.submitted"),
            Map.entry("HpdsSearchController.search", "SEARCH search.execute"),
            Map.entry("HpdsSearchController.values", "SEARCH search.values")
        )
    );

    @Test
    void everyMappedHandlerCarriesAuditEvent() throws Exception {
        List<String> unlabeled = new ArrayList<>();
        for (Method handler : mappedHandlers()) {
            if (handler.getAnnotation(AuditEvent.class) == null) {
                unlabeled.add(key(handler));
            }
        }
        assertThat(unlabeled).as("handlers missing @AuditEvent").isEmpty();
    }

    @Test
    void everyHandlerLabelMatchesTheGatewayRouteTable() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (Method handler : mappedHandlers()) {
            AuditEvent event = handler.getAnnotation(AuditEvent.class);
            String label = event == null ? "<none>" : event.type() + " " + event.action();
            assertThat(actual.put(key(handler), label)).as("overloaded handler %s", key(handler)).isNull();
        }
        assertThat(actual).isEqualTo(EXPECTED);
    }

    /**
     * Finds every handler method declared on a controller in this service.
     *
     * @return the methods carrying {@link RequestMapping} directly or through a composed annotation such as {@code @PostMapping}
     * @throws ClassNotFoundException if a scanned controller class cannot be loaded
     */
    private static List<Method> mappedHandlers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Method> handlers = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = ClassUtils.forName(candidate.getBeanClassName(), ControllerAuditEventTest.class.getClassLoader());
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    handlers.add(method);
                }
            }
        }
        assertThat(handlers).as("scan found no handlers under %s", BASE_PACKAGE).isNotEmpty();
        return handlers;
    }

    private static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "." + handler.getName();
    }
}
