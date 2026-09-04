package edu.harvard.hms.dbmi.avillach.auth.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the mass-assignment fix for the whole {@code rest} package at once: no request body may bind a JPA entity. Binding an entity lets
 * a caller set any column the entity exposes a setter for, including the inherited {@code uuid} and server-owned values such as
 * {@code Application.token} or {@code User.subject}/{@code passport}/{@code acceptedTOS}. Controllers must accept an allowlisted request
 * record from {@code auth.model.request} instead.
 */
class RequestBodyEntityBindingTest {

    private static final String CONTROLLER_PACKAGE = "edu.harvard.hms.dbmi.avillach.auth.rest";
    private static final String ENTITY_PACKAGE = "edu.harvard.hms.dbmi.avillach.auth.entity";

    @Test
    void noControllerBindsAPersistenceEntityToARequestBody() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(RequestBody.class)) {
                        continue;
                    }
                    for (Class<?> bound : boundTypes(parameter)) {
                        if (bound.getName().startsWith(ENTITY_PACKAGE)) {
                            offenders.add(controller.getSimpleName() + "#" + method.getName() + " binds " + bound.getSimpleName());
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(), "Request bodies must not bind JPA entities: " + offenders);
    }

    private static List<Class<?>> boundTypes(Parameter parameter) {
        List<Class<?>> types = new ArrayList<>();
        types.add(parameter.getType());
        if (parameter.getParameterizedType() instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof Class<?> argumentClass) {
                    types.add(argumentClass);
                }
            }
        }
        return types;
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents(CONTROLLER_PACKAGE);
        assertTrue(candidates.size() >= 7, "expected to scan the auth controllers, found " + candidates.size());

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : candidates) {
            try {
                classes.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new AssertionError("scanned a controller that will not load: " + candidate.getBeanClassName(), e);
            }
        }
        return classes;
    }
}
