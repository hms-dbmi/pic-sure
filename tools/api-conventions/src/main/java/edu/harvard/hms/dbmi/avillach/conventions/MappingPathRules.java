package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * The mapping path rules, as pure functions over imported classes. Each returns every violation it
 * finds rather than throwing on the first.
 */
public final class MappingPathRules {

    private static final List<String> PATH_PROPERTIES = List.of("value", "path");

    private MappingPathRules() {}

    /**
     * R16: no class-level or method-level mapping path on a controller ends in {@code /}. A class-level
     * path of exactly {@code /} is allowed. A method-level path of exactly {@code /} is allowed only when
     * the class declares no mapping path or only {@code /} and the empty path, because under any other
     * class path Spring joins the two into a path that ends in a slash: {@code /dataset/named} and
     * {@code /} serve {@code /dataset/named/}. Paths are read from the {@code value} and {@code path}
     * properties of {@code @RequestMapping} and of the composed {@code @GetMapping}, {@code @PostMapping},
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
            slashedPaths(controller.getAnnotations(), classLocation, List.of(), violations);
            List<String> classPaths = nonRootPaths(controller.getAnnotations());
            for (JavaMethod method : Controllers.handlerMethods(controller)) {
                String methodLocation = SwaggerRules.at(module, controller, method.getName());
                slashedPaths(method.getAnnotations(), methodLocation, classPaths, violations);
            }
        }
        return violations;
    }

    private static void slashedPaths(
        Set<? extends JavaAnnotation<?>> annotations, String location, List<String> classPaths, List<String> violations
    ) {
        for (JavaAnnotation<?> annotation : mappings(annotations)) {
            String where = location + " @" + annotation.getRawType().getSimpleName() + " path '";
            for (String path : paths(annotation)) {
                if (path.length() > 1 && path.endsWith("/")) {
                    violations.add(where + path + "' ends in a trailing slash");
                } else if (path.equals("/") && !classPaths.isEmpty()) {
                    String joined = String.join("', '", classPaths.stream().map(classPath -> classPath + "/").toList());
                    violations.add(where + "/' under a class path serves '" + joined + "' with a trailing slash");
                }
            }
        }
    }

    private static List<String> nonRootPaths(Set<? extends JavaAnnotation<?>> annotations) {
        return mappings(annotations).stream()
            .flatMap(annotation -> paths(annotation).stream())
            .filter(path -> !path.isEmpty() && !path.equals("/"))
            .toList();
    }

    private static List<JavaAnnotation<?>> mappings(Set<? extends JavaAnnotation<?>> annotations) {
        List<JavaAnnotation<?>> mappings = new ArrayList<>();
        for (JavaAnnotation<?> annotation : annotations) {
            if (Controllers.MAPPING_ANNOTATIONS.contains(annotation.getRawType().getName())) {
                mappings.add(annotation);
            }
        }
        return mappings;
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
