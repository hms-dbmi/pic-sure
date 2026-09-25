package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.Optional;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * Annotation lookup by fully qualified name. Matching on names rather than class literals keeps spring
 * and swagger off this module's main classpath, which matters because it reads bytecode compiled against
 * versions it does not itself depend on.
 */
public final class Annotations {

    public static final String CONTROLLER = "org.springframework.stereotype.Controller";
    public static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    public static final String TAG = "io.swagger.v3.oas.annotations.tags.Tag";
    public static final String HIDDEN = "io.swagger.v3.oas.annotations.Hidden";
    public static final String OPERATION = "io.swagger.v3.oas.annotations.Operation";
    public static final String API_RESPONSE = "io.swagger.v3.oas.annotations.responses.ApiResponse";
    public static final String API_RESPONSES = "io.swagger.v3.oas.annotations.responses.ApiResponses";

    private Annotations() {}

    /**
     * @param type the class to inspect
     * @param fullyQualifiedName the annotation type's name
     * @return whether the class carries that annotation, directly or as a meta-annotation
     */
    public static boolean has(JavaClass type, String fullyQualifiedName) {
        return get(type, fullyQualifiedName).isPresent();
    }

    /**
     * @param method the method to inspect
     * @param fullyQualifiedName the annotation type's name
     * @return whether the method carries that annotation, directly or as a meta-annotation
     */
    public static boolean has(JavaMethod method, String fullyQualifiedName) {
        return get(method, fullyQualifiedName).isPresent();
    }

    /**
     * @param type the class to inspect
     * @param fullyQualifiedName the annotation type's name
     * @return that annotation, looking through meta-annotations one level deep
     */
    public static Optional<JavaAnnotation<?>> get(JavaClass type, String fullyQualifiedName) {
        return find(type.getAnnotations(), fullyQualifiedName);
    }

    /**
     * @param method the method to inspect
     * @param fullyQualifiedName the annotation type's name
     * @return that annotation, looking through meta-annotations one level deep
     */
    public static Optional<JavaAnnotation<?>> get(JavaMethod method, String fullyQualifiedName) {
        return find(method.getAnnotations(), fullyQualifiedName);
    }

    /**
     * @param field the field to inspect
     * @param fullyQualifiedName the annotation type's name
     * @return whether the field carries that annotation, directly or as a meta-annotation
     */
    public static boolean has(JavaField field, String fullyQualifiedName) {
        return get(field, fullyQualifiedName).isPresent();
    }

    /**
     * @param field the field to inspect
     * @param fullyQualifiedName the annotation type's name
     * @return that annotation, looking through meta-annotations one level deep
     */
    public static Optional<JavaAnnotation<?>> get(JavaField field, String fullyQualifiedName) {
        return find(field.getAnnotations(), fullyQualifiedName);
    }

    /**
     * @param annotation the annotation to read
     * @param property the property name
     * @return the property's value as a string when it was set explicitly, empty when the annotation
     *     relies on that property's default. The rules do not depend on which of those two a given
     *     ArchUnit version reports, because every property they read has a blank default.
     */
    public static Optional<String> string(JavaAnnotation<?> annotation, String property) {
        return annotation.getProperties().containsKey(property)
            ? Optional.ofNullable(annotation.getProperties().get(property)).map(Object::toString)
            : Optional.empty();
    }

    private static Optional<JavaAnnotation<?>> find(Set<? extends JavaAnnotation<?>> annotations, String fullyQualifiedName) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (annotation.getRawType().getName().equals(fullyQualifiedName)) {
                return Optional.of(annotation);
            }
        }
        for (JavaAnnotation<?> annotation : annotations) {
            for (JavaAnnotation<?> meta : annotation.getRawType().getAnnotations()) {
                if (meta.getRawType().getName().equals(fullyQualifiedName)) {
                    return Optional.of(meta);
                }
            }
        }
        return Optional.empty();
    }
}
