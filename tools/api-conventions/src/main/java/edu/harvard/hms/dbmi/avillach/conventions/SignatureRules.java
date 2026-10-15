package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaGenericArrayType;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.core.domain.JavaWildcardType;

/**
 * The rules that keep handler signatures typed, as pure functions over imported classes. A handler whose
 * body or return is an open shape publishes an empty schema, so nothing tells a client what to send or what
 * comes back.
 */
public final class SignatureRules {

    public static final String REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";

    private static final String OBJECT = "java.lang.Object";
    private static final String MAP = "java.util.Map";
    private static final String JACKSON_TREE = "com.fasterxml.jackson.core.TreeNode";
    private static final String JACKSON_NODE = "com.fasterxml.jackson.databind.JsonNode";
    private static final String JACKSON_NODE_PACKAGE = "com.fasterxml.jackson.databind.node.";
    private static final String SLICE = "org.springframework.data.domain.Slice";

    private static final Set<String> PAGES = Set.of(
        "org.springframework.data.domain.Page",
        SLICE,
        "org.springframework.data.domain.PageImpl",
        "org.springframework.data.domain.SliceImpl"
    );

    private static final Set<String> WRAPPERS = Set.of(
        "org.springframework.http.ResponseEntity",
        "reactor.core.publisher.Mono",
        "java.util.Optional",
        "java.util.List",
        "java.util.Set"
    );

    private SignatureRules() {}

    /**
     * {@code typed-handler-signatures}: on every handler, the {@code @RequestBody} parameter type and the return
     * type name a concrete model. The check looks through {@code ResponseEntity}, {@code Mono}, {@code Optional},
     * {@code List}, {@code Set}, arrays and the type arguments of any other generic class, and at every level
     * rejects {@code Object}, a wildcard, a type variable, one of those five wrappers used raw, {@code Map} or any
     * {@code java.util} map type, {@code JsonNode} or another Jackson tree type, Spring Data's {@code Page} or
     * {@code Slice}, and a class annotated {@code @Entity}. Everything else passes, including {@code String},
     * {@code byte[]}, {@code InputStreamResource}, {@code Void} and {@code void}.
     *
     * <p>Only the {@code @RequestBody} parameter is read, because path variables, query parameters and injected
     * arguments are not the body. A handler marked {@code @Hidden} is checked like any other, because hidden
     * means absent from the document and the clients of an internal endpoint still need its shape. The rule sees
     * declared types only, so a {@code String} return whose body is JSON passes here and is caught by each
     * service's document test.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @param reactorEntities the names of entity classes compiled anywhere in the reactor, because an entity
     *     from another module arrives here as an unresolved type that carries no annotations
     * @return one violation per offending parameter or return, naming the declared type and each part of it
     *     that is not typed
     */
    public static List<String> typedHandlerSignatures(String module, JavaClasses classes, Set<String> reactorEntities) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                String where = SwaggerRules.at(module, controller, handler.getName());
                for (JavaParameter parameter : handler.getParameters()) {
                    if (parameter.isAnnotatedWith(REQUEST_BODY)) {
                        report(where + " parameter " + parameter.getIndex(), parameter.getType(), reactorEntities, violations);
                    }
                }
                report(where + " return", handler.getReturnType(), reactorEntities, violations);
            }
        }
        return violations;
    }

    private static void report(String position, JavaType declared, Set<String> reactorEntities, List<String> violations) {
        Set<String> reasons = new LinkedHashSet<>();
        collect(declared, reactorEntities, reasons);
        if (!reasons.isEmpty()) {
            violations.add(position + " " + render(declared) + " is not typed: " + String.join("; ", reasons));
        }
    }

    private static void collect(JavaType type, Set<String> reactorEntities, Set<String> reasons) {
        if (type instanceof JavaWildcardType wildcard) {
            reasons.add(render(wildcard) + " is a wildcard");
        } else if (type instanceof JavaTypeVariable<?> variable) {
            reasons.add(variable.getName() + " is a type variable");
        } else if (type instanceof JavaGenericArrayType array) {
            collect(array.getComponentType(), reactorEntities, reasons);
        } else if (type instanceof JavaParameterizedType parameterized) {
            Optional<String> fault = fault(parameterized.toErasure(), true, reactorEntities);
            if (fault.isPresent()) {
                reasons.add(render(parameterized) + " " + fault.get());
            } else {
                parameterized.getActualTypeArguments().forEach(argument -> collect(argument, reactorEntities, reasons));
            }
        } else if (type instanceof JavaClass raw) {
            if (raw.isArray()) {
                collect(raw.getComponentType(), reactorEntities, reasons);
            } else {
                fault(raw, false, reactorEntities).ifPresent(fault -> reasons.add(render(raw) + " " + fault));
            }
        }
    }

    private static Optional<String> fault(JavaClass raw, boolean parameterized, Set<String> reactorEntities) {
        String name = raw.getName();
        if (name.equals(OBJECT)) {
            return Optional.of("is untyped");
        }
        if (raw.isAssignableTo(MAP) || (name.startsWith("java.util.") && name.endsWith("Map"))) {
            return Optional.of("is a map");
        }
        if (raw.isAssignableTo(JACKSON_TREE) || name.equals(JACKSON_NODE) || name.startsWith(JACKSON_NODE_PACKAGE)) {
            return Optional.of("is a Jackson tree type");
        }
        if (raw.isAssignableTo(SLICE) || PAGES.contains(name)) {
            return Optional.of("is a Spring Data page or slice");
        }
        if (Annotations.has(raw, EntityBoundaryRules.ENTITY) || reactorEntities.contains(name)) {
            return Optional.of("is an @Entity");
        }
        if (!parameterized && WRAPPERS.contains(name)) {
            return Optional.of("is raw");
        }
        return Optional.empty();
    }

    private static String render(JavaType type) {
        if (type instanceof JavaParameterizedType parameterized) {
            List<String> arguments = parameterized.getActualTypeArguments().stream().map(SignatureRules::render).toList();
            return render(parameterized.toErasure()) + "<" + String.join(", ", arguments) + ">";
        }
        if (type instanceof JavaGenericArrayType array) {
            return render(array.getComponentType()) + "[]";
        }
        if (type instanceof JavaWildcardType wildcard) {
            if (!wildcard.getLowerBounds().isEmpty()) {
                return "? super " + render(wildcard.getLowerBounds().get(0));
            }
            return wildcard.getUpperBounds().isEmpty() ? "?" : "? extends " + render(wildcard.getUpperBounds().get(0));
        }
        if (type instanceof JavaClass raw) {
            return raw.isArray() ? render(raw.getComponentType()) + "[]" : raw.getSimpleName();
        }
        return type.getName();
    }
}
