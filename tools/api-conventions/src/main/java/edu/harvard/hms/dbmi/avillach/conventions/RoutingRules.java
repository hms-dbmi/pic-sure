package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;

/**
 * The request routing rules, as pure functions over imported classes. They check that what a handler
 * binds from the request can actually arrive in the request its mapping matches.
 */
public final class RoutingRules {

    public static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    public static final String PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";

    private static final List<String> METHOD_MAPPINGS = List.of(
        REQUEST_MAPPING,
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
        "org.springframework.web.bind.annotation.PatchMapping"
    );

    private RoutingRules() {}

    /**
     * R17: every {@code @PathVariable} that names its variable, through {@code value} or {@code name},
     * appears as {@code {name}} or {@code {name:regex}} in at least one path the handler maps. Those paths
     * are every combination of the class-level {@code @RequestMapping} paths with the method-level mapping
     * paths, each read from both {@code value} and {@code path}. A variable no path declares is never
     * bound, so Spring answers every request to that handler with a 500.
     *
     * <p>A {@code @PathVariable} with no explicit name is skipped. Its name comes from the compiled
     * parameter name, which ArchUnit cannot read. The reactor compiles with {@code -parameters}, and
     * {@code DashboardDrawerControllerParameterNameTest} guards that those names survive compilation.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per named path variable that no mapped path declares
     */
    public static List<String> pathVariablesAppearInTemplate(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            List<String> prefixes = paths(directAnnotation(controller.getAnnotations(), List.of(REQUEST_MAPPING)));
            for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                List<String> suffixes = paths(directAnnotation(handler.getAnnotations(), METHOD_MAPPINGS));
                List<String> templates = combine(prefixes, suffixes);
                Set<String> declared = new LinkedHashSet<>();
                templates.forEach(template -> declared.addAll(templateVariables(template)));
                for (String name : pathVariableNames(handler)) {
                    if (!declared.contains(name)) {
                        String at = SwaggerRules.at(module, controller, handler.getName());
                        violations.add(at + " binds @PathVariable(\"" + name + "\") but no mapped path " + templates
                            + " declares {" + name + "}");
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Reads the variable names a Spring path template declares. Accepts {@code {name}},
     * {@code {name:regex}} with braces nested inside the regex, and the {@code {*name}} capture form.
     *
     * @param template one mapped path
     * @return the declared variable names in order of appearance
     */
    public static List<String> templateVariables(String template) {
        List<String> names = new ArrayList<>();
        int index = 0;
        while ((index = template.indexOf('{', index)) >= 0) {
            int depth = 0;
            int end = index;
            while (end < template.length()) {
                char next = template.charAt(end);
                depth += next == '{' ? 1 : next == '}' ? -1 : 0;
                if (depth == 0) {
                    break;
                }
                end++;
            }
            String body = template.substring(index + 1, Math.min(end, template.length()));
            int colon = body.indexOf(':');
            String name = colon >= 0 ? body.substring(0, colon) : body;
            names.add(name.startsWith("*") ? name.substring(1).strip() : name.strip());
            index = end + 1;
        }
        return names;
    }

    private static List<String> pathVariableNames(JavaMethod handler) {
        List<String> names = new ArrayList<>();
        for (JavaParameter parameter : handler.getParameters()) {
            directAnnotation(parameter.getAnnotations(), List.of(PATH_VARIABLE))
                .flatMap(RoutingRules::explicitName)
                .ifPresent(names::add);
        }
        return names;
    }

    private static Optional<String> explicitName(JavaAnnotation<?> pathVariable) {
        return Stream.of("value", "name")
            .map(property -> Annotations.string(pathVariable, property).orElse(""))
            .filter(name -> !name.isBlank())
            .findFirst();
    }

    private static Optional<JavaAnnotation<?>> directAnnotation(
        Set<? extends JavaAnnotation<?>> annotations, List<String> fullyQualifiedNames
    ) {
        return annotations.stream()
            .filter(annotation -> fullyQualifiedNames.contains(annotation.getRawType().getName()))
            .<JavaAnnotation<?>>map(annotation -> annotation)
            .findFirst();
    }

    private static List<String> paths(Optional<JavaAnnotation<?>> mapping) {
        List<String> paths = new ArrayList<>();
        mapping.ifPresent(annotation -> Stream.of("value", "path").forEach(property -> paths.addAll(strings(annotation, property))));
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static List<String> strings(JavaAnnotation<?> annotation, String property) {
        Object value = annotation.getProperties().get(property);
        if (value instanceof String[] values) {
            return List.of(values);
        }
        return value instanceof String single ? List.of(single) : List.of();
    }

    private static List<String> combine(List<String> prefixes, List<String> suffixes) {
        List<String> combined = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String suffix : suffixes) {
                combined.add(prefix + suffix);
            }
        }
        return combined;
    }
}
