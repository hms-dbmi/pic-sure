package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaGenericArrayType;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.core.domain.JavaWildcardType;

/**
 * The rules that keep request and response models documented, as pure functions over imported classes. A
 * model with no {@code @Schema} still reaches the served document, where its fields appear with a type and
 * nothing that says what they mean or what a real value looks like.
 */
public final class SchemaRules {

    public static final String SCHEMA = "io.swagger.v3.oas.annotations.media.Schema";
    public static final String JSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore";
    public static final String JSON_SUB_TYPES = "com.fasterxml.jackson.annotation.JsonSubTypes";

    /** The shared model libraries whose classes the documented services bind and return. */
    public static final List<String> SHARED_MODEL_MODULES =
        List.of("libs/pic-sure-commons/pic-sure-api-model", "libs/pic-sure-commons/pic-sure-hpds-model");

    private static final List<String> FRAMEWORK_PACKAGES =
        List.of("java.", "javax.", "jdk.", "com.fasterxml.jackson.", "org.springframework.", "reactor.");

    private static final Set<String> SCALARS = Set.of(
        "byte", "short", "int", "long", "float", "double", "char",
        "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
        "java.lang.Character", "java.lang.String", "java.util.UUID", "java.time.Instant", "java.util.Date", "java.time.LocalDate"
    );

    private static final String COLLECTION = "java.util.Collection";
    private static final Set<String> COLLECTIONS = Set.of(COLLECTION, "java.util.List", "java.util.Set");

    private static final Set<JavaModifier> NOT_SERIALISED = Set.of(JavaModifier.STATIC, JavaModifier.SYNTHETIC, JavaModifier.TRANSIENT);

    private SchemaRules() {}

    /**
     * {@code schema-documented-models}: every model a documented handler binds or returns, and every model
     * reachable from one, is documented with {@code @Schema}.
     *
     * <p>The walk starts at each handler's {@code @RequestBody} parameter type and return type in the modules the
     * registry marks documented, hidden handlers included. From a type it follows generic arguments, array
     * component types, wildcard and type variable bounds, the superclass, the classes a {@code @JsonSubTypes}
     * names, and the type of every serialised field. It stops at primitives and at JDK, Jackson, Spring and
     * Reactor types, whose generic arguments are still followed. A class is looked up by name across the
     * documented modules and the shared model libraries, because a type another module compiled arrives in the
     * referring module as an unresolved stub with no fields and no annotations.
     *
     * <p>Every class reached carries a class-level {@code @Schema} with a non-blank description. Every
     * serialised field carries a {@code @Schema} with a non-blank description, read from the field or from its
     * accessor. A record component's annotation is copied onto its field by the compiler, so records are read
     * the same way as classes. A field whose type is a scalar, or a collection or array of scalars at any
     * depth, also carries a non-blank example. The scalars are the numeric and character primitives and their
     * boxes, {@code String}, {@code UUID}, {@code Instant}, {@code Date} and {@code LocalDate}. Every other type
     * is exempt from the example: booleans, enums, nested models, maps, {@code Object} and type variables. An
     * enum carries a description on the type and on each constant, and its other fields are not read.
     *
     * <p>A field is not serialised, and so not checked or followed, when it is static, synthetic or transient,
     * or when it or its accessor carries {@code @JsonIgnore}.
     *
     * @param registry the parsed registry, which names the documented modules
     * @param modules module path mapped to that module's imported classes
     * @param sharedModelModules the module paths of the libraries that hold models the services share
     * @return one violation per missing description or example, per shared library that was not compiled, and
     *     per reached class that no checked module compiled
     */
    public static List<String> documentedModels(
        ModuleRegistry registry, Map<String, JavaClasses> modules, List<String> sharedModelModules
    ) {
        List<String> violations = new ArrayList<>();
        Map<String, JavaClasses> checked = new LinkedHashMap<>();
        for (String module : registry.documentedModules()) {
            if (modules.containsKey(module)) {
                checked.put(module, modules.get(module));
            }
        }
        List<String> documented = List.copyOf(checked.keySet());
        for (String library : sharedModelModules) {
            if (modules.containsKey(library)) {
                checked.put(library, modules.get(library));
            } else {
                violations.add(library + " holds shared models but was not compiled (run make build)");
            }
        }
        Walk walk = new Walk(checked, new TreeSet<>(), violations);
        for (String module : documented) {
            for (JavaClass controller : Controllers.of(checked.get(module))) {
                for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                    String from = SwaggerRules.at(module, controller, handler.getName());
                    for (JavaParameter parameter : handler.getParameters()) {
                        if (parameter.isAnnotatedWith(SignatureRules.REQUEST_BODY)) {
                            walk.follow(parameter.getType(), module, from, new TreeSet<>());
                        }
                    }
                    walk.follow(handler.getReturnType(), module, from, new TreeSet<>());
                }
            }
        }
        return violations;
    }

    /**
     * One pass over the model graph.
     *
     * @param checked module path mapped to the classes of every module the rule reads
     * @param reached the names of the classes already visited, so each is checked once
     * @param violations the list every finding is added to
     */
    private record Walk(Map<String, JavaClasses> checked, Set<String> reached, List<String> violations) {

        private void follow(JavaType type, String module, String from, Set<String> variables) {
            if (type instanceof JavaParameterizedType parameterized) {
                follow(parameterized.toErasure(), module, from, variables);
                parameterized.getActualTypeArguments().forEach(argument -> follow(argument, module, from, variables));
            } else if (type instanceof JavaGenericArrayType array) {
                follow(array.getComponentType(), module, from, variables);
            } else if (type instanceof JavaWildcardType wildcard) {
                wildcard.getUpperBounds().forEach(bound -> follow(bound, module, from, variables));
                wildcard.getLowerBounds().forEach(bound -> follow(bound, module, from, variables));
            } else if (type instanceof JavaTypeVariable<?> variable) {
                if (variables.add(variable.getName())) {
                    variable.getUpperBounds().forEach(bound -> follow(bound, module, from, variables));
                }
            } else if (type instanceof JavaClass raw) {
                if (raw.isArray()) {
                    follow(raw.getComponentType(), module, from, variables);
                } else {
                    reach(raw.getName(), module, from);
                }
            }
        }

        private void reach(String name, String module, String from) {
            if (isFramework(name) || !reached.add(name)) {
                return;
            }
            Optional<String> owner = Stream.concat(Stream.of(module), checked.keySet().stream())
                .filter(candidate -> checked.get(candidate).contain(name))
                .findFirst();
            if (owner.isEmpty()) {
                violations.add(
                    from + " reaches " + name + ", which no documented module or shared model library compiles,"
                        + " so its @Schema cannot be read"
                );
                return;
            }
            check(owner.get(), checked.get(owner.get()).get(name));
        }

        private void check(String module, JavaClass model) {
            String at = SwaggerRules.at(module, model);
            if (text(Annotations.get(model, SCHEMA), "description").isEmpty()) {
                violations.add(at + " is missing a class-level @Schema description");
            }
            if (model.isEnum()) {
                for (JavaField constant : fields(model)) {
                    boolean isConstant = constant.getModifiers().contains(JavaModifier.ENUM);
                    if (isConstant && text(schemaOn(model, constant), "description").isEmpty()) {
                        violations.add(SwaggerRules.at(module, model, constant.getName()) + " is missing a @Schema description");
                    }
                }
                return;
            }
            List<JavaField> serialised = fields(model).stream().filter(field -> isSerialised(model, field)).toList();
            for (JavaField field : serialised) {
                String where = SwaggerRules.at(module, model, field.getName());
                Optional<JavaAnnotation<?>> schema = schemaOn(model, field);
                if (text(schema, "description").isEmpty()) {
                    violations.add(where + " is missing a @Schema description");
                }
                if (needsExample(field.getType()) && text(schema, "example").isEmpty()) {
                    violations.add(where + " is missing a @Schema example");
                }
            }
            model.getRawSuperclass().ifPresent(parent -> reach(parent.getName(), module, at));
            subtypes(model).forEach(subtype -> reach(subtype, module, at));
            for (JavaField field : serialised) {
                follow(field.getType(), module, SwaggerRules.at(module, model, field.getName()), new TreeSet<>());
            }
        }
    }

    private static boolean isFramework(String name) {
        return !name.contains(".") || FRAMEWORK_PACKAGES.stream().anyMatch(name::startsWith);
    }

    private static List<JavaField> fields(JavaClass model) {
        return model.getFields().stream().sorted(Comparator.comparing(JavaField::getName)).toList();
    }

    private static boolean isSerialised(JavaClass model, JavaField field) {
        if (field.getModifiers().stream().anyMatch(NOT_SERIALISED::contains) || field.isAnnotatedWith(JSON_IGNORE)) {
            return false;
        }
        return accessors(model, field).noneMatch(accessor -> accessor.isAnnotatedWith(JSON_IGNORE));
    }

    private static Optional<JavaAnnotation<?>> schemaOn(JavaClass model, JavaField field) {
        Optional<JavaAnnotation<?>> onField = field.tryGetAnnotationOfType(SCHEMA).map(annotation -> annotation);
        if (onField.isPresent()) {
            return onField;
        }
        return accessors(model, field)
            .flatMap(accessor -> accessor.tryGetAnnotationOfType(SCHEMA).stream())
            .<JavaAnnotation<?>>map(annotation -> annotation)
            .findFirst();
    }

    private static Stream<JavaMethod> accessors(JavaClass model, JavaField field) {
        String name = field.getName();
        String capitalised = name.substring(0, 1).toUpperCase() + name.substring(1);
        return Stream.of(name, "get" + capitalised, "is" + capitalised).flatMap(accessor -> model.tryGetMethod(accessor).stream());
    }

    private static List<String> subtypes(JavaClass model) {
        Object entries = Annotations.get(model, JSON_SUB_TYPES).map(annotation -> annotation.getProperties().get("value")).orElse(null);
        if (!(entries instanceof Object[] types)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object type : types) {
            if (type instanceof JavaAnnotation<?> entry && entry.getProperties().get("value") instanceof JavaClass subtype) {
                names.add(subtype.getName());
            }
        }
        return names;
    }

    private static Optional<String> text(Optional<JavaAnnotation<?>> schema, String property) {
        return schema.flatMap(annotation -> Annotations.string(annotation, property)).filter(value -> !value.isBlank());
    }

    private static boolean needsExample(JavaType type) {
        if (type instanceof JavaParameterizedType parameterized) {
            JavaClass raw = parameterized.toErasure();
            List<JavaType> arguments = parameterized.getActualTypeArguments();
            boolean collection = raw.isAssignableTo(COLLECTION) || COLLECTIONS.contains(raw.getName());
            return collection && arguments.size() == 1 && needsExample(arguments.get(0));
        }
        if (type instanceof JavaGenericArrayType array) {
            return needsExample(array.getComponentType());
        }
        if (type instanceof JavaClass raw) {
            return raw.isArray() ? needsExample(raw.getComponentType()) : SCALARS.contains(raw.getName());
        }
        return false;
    }
}
