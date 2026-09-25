package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * The exception advice rules, as pure functions over imported classes.
 *
 * <p>Spring's {@code ExceptionHandlerExceptionResolver} consults every {@code @ControllerAdvice} before
 * {@code DefaultHandlerExceptionResolver}, so an advice that handles {@code Exception} answers for the
 * framework's own client errors too: an unreadable body, an unsupported media type, a wrong method or a
 * missing parameter all come back as 500. {@code ResponseEntityExceptionHandler} declares a closer handler
 * for each of those, which Spring prefers over the catch-all, so extending it restores their statuses.
 */
public final class ExceptionAdviceRules {

    public static final String CONTROLLER_ADVICE = "org.springframework.web.bind.annotation.ControllerAdvice";
    public static final String REST_CONTROLLER_ADVICE = "org.springframework.web.bind.annotation.RestControllerAdvice";
    public static final String EXCEPTION_HANDLER = "org.springframework.web.bind.annotation.ExceptionHandler";
    public static final String RESPONSE_ENTITY_EXCEPTION_HANDLER =
        "org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler";

    private static final Set<String> CATCH_ALL_TYPES = Set.of("java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable");

    private ExceptionAdviceRules() {}

    /**
     * R13: a {@code @ControllerAdvice} or {@code @RestControllerAdvice} class that declares an
     * {@code @ExceptionHandler} for {@code Exception}, {@code RuntimeException} or {@code Throwable}
     * extends {@code ResponseEntityExceptionHandler}. The handled types come from the annotation's
     * {@code value} (or its {@code exception} alias), and from the method's throwable parameters when the
     * annotation names none.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per catch-all handler method in an advice that does not extend the base class
     */
    public static List<String> catchAllAdviceExtendsBaseHandler(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : sorted(classes)) {
            if (!isAdvice(type) || type.isAssignableTo(RESPONSE_ENTITY_EXCEPTION_HANDLER)) {
                continue;
            }
            for (JavaMethod method : sortedMethods(type)) {
                Optional<JavaAnnotation<?>> handler = Annotations.get(method, EXCEPTION_HANDLER);
                if (handler.isEmpty()) {
                    continue;
                }
                List<String> catchAll = handledTypes(handler.get(), method).stream().filter(CATCH_ALL_TYPES::contains).toList();
                if (!catchAll.isEmpty()) {
                    String names = String.join(", ", catchAll.stream().map(ExceptionAdviceRules::simpleName).toList());
                    violations.add(
                        SwaggerRules.at(module, type, method.getName()) + " handles " + names + " but " + type.getSimpleName()
                            + " does not extend ResponseEntityExceptionHandler"
                    );
                }
            }
        }
        return violations;
    }

    /**
     * Reads the exception types one {@code @ExceptionHandler} method handles.
     *
     * @param handler the method's {@code @ExceptionHandler} annotation
     * @param method the annotated method
     * @return the fully qualified names of the declared types, or of the method's throwable parameter
     *     types when the annotation declares none
     */
    static Set<String> handledTypes(JavaAnnotation<?> handler, JavaMethod method) {
        Set<String> declared = new LinkedHashSet<>();
        declared.addAll(classNames(handler, "value"));
        declared.addAll(classNames(handler, "exception"));
        if (!declared.isEmpty()) {
            return declared;
        }
        Set<String> fromParameters = new LinkedHashSet<>();
        for (JavaClass parameter : method.getRawParameterTypes()) {
            if (parameter.isAssignableTo(Throwable.class)) {
                fromParameters.add(parameter.getName());
            }
        }
        return fromParameters;
    }

    private static List<String> classNames(JavaAnnotation<?> annotation, String property) {
        Object value = annotation.getProperties().get(property);
        List<String> names = new ArrayList<>();
        if (value instanceof JavaClass[] types) {
            for (JavaClass type : types) {
                names.add(type.getName());
            }
        } else if (value instanceof JavaClass type) {
            names.add(type.getName());
        }
        return names;
    }

    private static boolean isAdvice(JavaClass type) {
        return Annotations.has(type, CONTROLLER_ADVICE) || Annotations.has(type, REST_CONTROLLER_ADVICE);
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static List<JavaClass> sorted(JavaClasses classes) {
        return classes.stream().sorted(Comparator.comparing(JavaClass::getName)).toList();
    }

    private static List<JavaMethod> sortedMethods(JavaClass type) {
        return type.getMethods().stream().sorted(Comparator.comparing(JavaMethod::getName)).toList();
    }
}
