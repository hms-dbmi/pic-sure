package edu.harvard.hms.dbmi.avillach.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * Pins the {@link AuditEvent} label on every request handler in this service. Controllers are found by a classpath scan, so a new
 * controller or handler without a label fails here without any Spring context.
 */
class ControllerAuditEventTest {

    private static final Map<String, String> EXPECTED = new TreeMap<>(
        Map.ofEntries(
            Map.entry("BannerController.activeBanners", "OTHER banner.list_active"),
            Map.entry("BannerController.managedBanners", "OTHER banner.list"),
            Map.entry("BannerController.reorder", "ADMIN banner.reordered"),
            Map.entry("BannerController.publish", "ADMIN banner.published"), Map.entry("BannerController.saveDraft", "ADMIN banner.saved"),
            Map.entry("BannerController.update", "ADMIN banner.updated"),
            Map.entry("BannerController.publishDraft", "ADMIN banner.published"),
            Map.entry("BannerController.disable", "ADMIN banner.disabled"), Map.entry("BannerController.archive", "ADMIN banner.archived"),
            Map.entry("BannerController.restore", "ADMIN banner.restored"),
            Map.entry("ConfigurationController.getConfigurations", "OTHER configuration.list"),
            Map.entry("ConfigurationController.getConfigurationById", "OTHER configuration.read"),
            Map.entry("ConfigurationController.addConfiguration", "ADMIN configuration.modify"),
            Map.entry("ConfigurationController.updateConfiguration", "ADMIN configuration.modify"),
            Map.entry("ConfigurationController.deleteConfiguration", "ADMIN configuration.delete"),
            Map.entry("NamedDatasetController.list", "ACCESS named_dataset.list"),
            Map.entry("NamedDatasetController.get", "ACCESS named_dataset.read"),
            Map.entry("NamedDatasetController.create", "ACCESS named_dataset.modify"),
            Map.entry("NamedDatasetController.update", "ACCESS named_dataset.modify"),
            Map.entry("NamedDatasetController.delete", "ACCESS named_dataset.delete"),
            Map.entry("InternalQueryController.save", "OTHER internal_query.save"),
            Map.entry("InternalQueryController.get", "OTHER internal_query.read"),
            Map.entry("InternalQueryController.update", "OTHER internal_query.update"),
            Map.entry("InternalQueryController.dispatch", "OTHER internal_query.dispatch")
        )
    );

    @Test
    void everyMappedHandlerCarriesAuditEvent() throws Exception {
        List<String> unlabeled = new ArrayList<>();
        for (Method handler : handlers()) {
            if (handler.getAnnotation(AuditEvent.class) == null) {
                unlabeled.add(key(handler));
            }
        }
        assertThat(unlabeled).as("handlers without @AuditEvent").isEmpty();
    }

    @Test
    void everyHandlerCarriesItsPinnedLabel() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (Method handler : handlers()) {
            AuditEvent event = handler.getAnnotation(AuditEvent.class);
            actual.put(key(handler), event == null ? "none" : event.type() + " " + event.action());
        }
        assertThat(actual).isEqualTo(EXPECTED);
    }

    /**
     * Finds every public method with a request mapping on a {@code @Controller} or {@code @RestController} in this service.
     *
     * @return the handler methods, in no particular order
     * @throws ClassNotFoundException if a scanned controller class cannot be loaded
     */
    private static List<Method> handlers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Method> handlers = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(ControllerAuditEventTest.class.getPackageName())) {
            Class<?> controller = ClassUtils.forName(candidate.getBeanClassName(), ControllerAuditEventTest.class.getClassLoader());
            for (Method method : controller.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    handlers.add(method);
                }
            }
        }
        assertThat(handlers).as("scanned handlers").isNotEmpty();
        return handlers;
    }

    private static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "." + handler.getName();
    }
}
