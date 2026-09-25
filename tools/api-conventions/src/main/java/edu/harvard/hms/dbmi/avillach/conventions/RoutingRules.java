package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * The request routing rules, as pure functions over imported classes. Each returns every violation it
 * finds rather than throwing on the first.
 */
public final class RoutingRules {

    private static final List<String> PATH_PROPERTIES = List.of("value", "path");

    private RoutingRules() {}

    /**
     * R16: no class-level or method-level mapping path on a controller ends in {@code /}, except a path
     * that is exactly {@code /}. Paths are read from the {@code value} and {@code path} properties of
     * {@code @RequestMapping} and of the composed {@code @GetMapping}, {@code @PostMapping},
     * {@code @PutMapping}, {@code @DeleteMapping} and {@code @PatchMapping}.
     *
     * <p>Spring 6 matches only the declared form of a path, so a handler declared with a trailing slash
     * answers 404 to a client that calls it without one, while tests that call the declared form pass.
     * The rule is applied to documented modules only, because their clients call slash-less paths. An
     * internal module may declare a trailing slash that its one caller sends on purpose, as the hpds
     * {@code /search/values/} mappings do for {@code ResourceWebClient}.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per mapping path that ends in a slash
     */
    public static List<String> noTrailingSlash(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            String classLocation = SwaggerRules.at(module, controller);
            slashedPaths(controller.getAnnotations(), classLocation, violations);
            for (JavaMethod method : Controllers.handlerMethods(controller)) {
                slashedPaths(method.getAnnotations(), SwaggerRules.at(module, controller, method.getName()), violations);
            }
        }
        return violations;
    }

    private static void slashedPaths(Set<? extends JavaAnnotation<?>> annotations, String location, List<String> violations) {
        for (JavaAnnotation<?> annotation : annotations) {
            String type = annotation.getRawType().getName();
            if (!Controllers.MAPPING_ANNOTATIONS.contains(type)) {
                continue;
            }
            for (String path : paths(annotation)) {
                if (path.length() > 1 && path.endsWith("/")) {
                    violations.add(location + " @" + annotation.getRawType().getSimpleName() + " path '" + path
                        + "' ends in a trailing slash");
                }
            }
        }
    }

    private static List<String> paths(JavaAnnotation<?> annotation) {
        List<String> paths = new ArrayList<>();
        for (String property : PATH_PROPERTIES) {
            Object value = annotation.getProperties().get(property);
            if (value instanceof Object[] entries) {
                for (Object entry : entries) {
                    paths.add(String.valueOf(entry));
                }
            } else if (value instanceof String single) {
                paths.add(single);
            }
        }
        return paths;
    }
}
