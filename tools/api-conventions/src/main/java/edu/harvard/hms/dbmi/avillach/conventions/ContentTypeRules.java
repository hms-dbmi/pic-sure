package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * R14: the request content type rules, as pure functions over imported classes. Each returns every
 * violation it finds rather than throwing on the first.
 */
public final class ContentTypeRules {

    static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
    static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    static final String ANY_MEDIA_TYPE = "*/*";

    private ContentTypeRules() {}

    /**
     * R14: a handler that answers GET sets no {@code consumes} other than the wildcard media type.
     * A GET carries no body, so clients send no {@code Content-Type}, and Spring never matches such a
     * request to the handler: it answers 415, or hands the request to another mapping that fits the path.
     * A handler answers GET when it carries {@code @GetMapping}, or a {@code @RequestMapping} whose
     * {@code method} includes GET or is left empty. Its effective {@code consumes} is its own when set,
     * and otherwise the one on its class's {@code @RequestMapping}, which Spring applies to every handler
     * that does not declare its own.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per GET handler whose effective {@code consumes} names a narrower type
     */
    public static List<String> getDoesNotNarrowConsumes(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            List<String> inherited = direct(controller.getAnnotations(), REQUEST_MAPPING)
                .map(ContentTypeRules::consumes)
                .orElse(List.of());
            for (JavaMethod method : Controllers.handlerMethods(controller)) {
                Optional<JavaAnnotation<?>> mapping = getMapping(method);
                if (mapping.isEmpty()) {
                    continue;
                }
                List<String> own = consumes(mapping.get());
                List<String> effective = own.isEmpty() ? inherited : own;
                if (effective.stream().anyMatch(type -> !ANY_MEDIA_TYPE.equals(type))) {
                    String source = own.isEmpty() ? " inherits consumes " : " sets consumes ";
                    violations.add(
                        SwaggerRules.at(module, controller, method.getName()) + " answers GET but" + source + effective
                            + "; a GET has no body, so drop it or use */*"
                    );
                }
            }
        }
        return violations;
    }

    private static Optional<JavaAnnotation<?>> getMapping(JavaMethod method) {
        Optional<JavaAnnotation<?>> get = direct(method.getAnnotations(), GET_MAPPING);
        if (get.isPresent()) {
            return get;
        }
        return direct(method.getAnnotations(), REQUEST_MAPPING).filter(ContentTypeRules::includesGet);
    }

    private static boolean includesGet(JavaAnnotation<?> requestMapping) {
        List<String> methods = values(requestMapping, "method");
        return methods.isEmpty() || methods.contains("GET");
    }

    private static List<String> consumes(JavaAnnotation<?> mapping) {
        return values(mapping, "consumes");
    }

    private static List<String> values(JavaAnnotation<?> annotation, String property) {
        Object value = annotation.getProperties().get(property);
        if (!(value instanceof Object[] entries)) {
            return List.of();
        }
        return Arrays.stream(entries)
            .map(entry -> entry instanceof JavaEnumConstant constant ? constant.name() : String.valueOf(entry))
            .toList();
    }

    private static Optional<JavaAnnotation<?>> direct(
        Set<? extends JavaAnnotation<?>> annotations, String fullyQualifiedName
    ) {
        return annotations.stream()
            .filter(annotation -> annotation.getRawType().getName().equals(fullyQualifiedName))
            .<JavaAnnotation<?>>map(annotation -> annotation)
            .findFirst();
    }
}
