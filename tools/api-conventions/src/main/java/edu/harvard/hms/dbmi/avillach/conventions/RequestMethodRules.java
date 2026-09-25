package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * The rules on which HTTP verbs a handler answers, as pure functions over imported classes.
 */
public final class RequestMethodRules {

    public static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";

    private RequestMethodRules() {}

    /**
     * R12: every handler method that carries {@code @RequestMapping} itself names at least one verb in its
     * {@code method} property. A method-level {@code @RequestMapping} without one maps every HTTP verb, so
     * the endpoint answers GET, PUT, PATCH, DELETE, HEAD and OPTIONS alongside the verb its callers use, and
     * the published document lists all seven. Composed annotations such as {@code @GetMapping} fix their verb and are
     * out of scope, as is a class-level {@code @RequestMapping}, which only sets a path prefix.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per handler whose {@code @RequestMapping} names no verb
     */
    public static List<String> requestMappingNamesMethod(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                Optional<JavaAnnotation<?>> mapping = directRequestMapping(handler);
                if (mapping.isPresent() && namesNoMethod(mapping.get())) {
                    String at = SwaggerRules.at(module, controller, handler.getName());
                    violations.add(at + " carries @RequestMapping with no method, so it answers every HTTP verb");
                }
            }
        }
        return violations;
    }

    private static Optional<JavaAnnotation<?>> directRequestMapping(JavaMethod method) {
        return method.getAnnotations().stream()
            .filter(annotation -> annotation.getRawType().getName().equals(REQUEST_MAPPING))
            .<JavaAnnotation<?>>map(annotation -> annotation)
            .findFirst();
    }

    private static boolean namesNoMethod(JavaAnnotation<?> mapping) {
        Object methods = mapping.getProperties().get("method");
        return !(methods instanceof Object[] array) || array.length == 0;
    }
}
