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

    /**
     * The value fields that must have no example, as {@code fully.qualified.Model#field}. Each names a field
     * whose only honest example is a value the deployment configures, which the document would otherwise state
     * as if it were fixed.
     */
    public static final Set<String> WITHOUT_EXAMPLE =
        Set.of("edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount#variance");

    private static final List<String> FRAMEWORK_PACKAGES =
        List.of("java.", "javax.", "jdk.", "com.fasterxml.jackson.", "org.springframework.", "reactor.");

    private static final List<String> JDK_PACKAGES = List.of("java.", "javax.", "jdk.");

    private static final Set<String> NO_EXAMPLE =
        Set.of("boolean", "java.lang.Boolean", "java.lang.Object", "java.lang.Void", "java.util.Optional");

    private static final String OPTIONAL = "java.util.Optional";

    private static final String COLLECTION = "java.util.Collection";
    private static final Set<String> COLLECTIONS = Set.of(COLLECTION, "java.util.List", "java.util.Set");

    private static final String MAP = "java.util.Map";
    private static final Set<String> MAPS = Set.of(
        MAP, "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.SortedMap", "java.util.NavigableMap",
        "java.util.concurrent.ConcurrentMap", "java.util.concurrent.ConcurrentHashMap"
    );

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
     * serialised field carries a {@code @Schema} with a non-blank description. The description and the example
     * are each read from whichever of the field and its accessors sets them, so a field can carry the example
     * while its getter carries the description. A record component's annotation is copied onto its field by the
     * compiler, so records are read the same way as classes. A field whose type is a value also carries a
     * non-blank example, and so does a collection, array or {@code Optional} of values at any depth. A value is
     * a primitive other than {@code boolean}, or a JDK class that is not exempt. The exempt types are
     * {@code Boolean}, {@code Object}, {@code Void}, enums and maps, and every type outside the JDK: nested
     * models, Jackson trees such as {@code JsonNode}, and type variables. Requiring the example of every JDK
     * class rather than of a fixed list means a {@code BigDecimal} or an {@code OffsetDateTime} cannot slip
     * through. An enum carries a description on the type and on each constant, and its other fields are not
     * read.
     *
     * <p>A field is not serialised, and so not checked or followed, when it is static, synthetic or transient,
     * or when it or its accessor carries {@code @JsonIgnore}. A field whose {@code @Schema}, on the field or on
     * an accessor, sets {@code hidden = true} is left out of the document, so it is neither checked nor
     * followed either.
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
        return documentedModels(registry, modules, sharedModelModules, Set.of());
    }

    /**
     * The same rule, except that the named value fields must have no example. An exemption is for a field whose
     * only honest example is a value the deployment configures. Each must name a value field of a reached model
     * that has no example, so an exemption that no longer applies fails rather than lingers: a listed field that
     * carries an example is a violation, and so is a listed name the walk never checks as a value field.
     *
     * @param registry the parsed registry, which names the documented modules
     * @param modules module path mapped to that module's imported classes
     * @param sharedModelModules the module paths of the libraries that hold models the services share
     * @param withoutExample the fields that must have no example, as {@code fully.qualified.Model#field}
     * @return the violations of {@link #documentedModels(ModuleRegistry, Map, List)}, plus one per exemption
     *     that does not apply
     */
    public static List<String> documentedModels(
        ModuleRegistry registry, Map<String, JavaClasses> modules, List<String> sharedModelModules, Set<String> withoutExample
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
        Walk walk = new Walk(checked, new TreeSet<>(), violations, withoutExample, new TreeSet<>());
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
        for (String label : withoutExample) {
            if (!walk.exemptionsApplied().contains(label)) {
                violations.add(label + " is exempt from the example rule but is not a value field of a reached model");
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
     * @param withoutExample the fields that must have no example, as {@code fully.qualified.Model#field}
     * @param exemptionsApplied the entries of {@code withoutExample} the walk has checked as value fields
     */
    private record Walk(
        Map<String, JavaClasses> checked, Set<String> reached, List<String> violations, Set<String> withoutExample,
        Set<String> exemptionsApplied
    ) {

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
                    if (isConstant && text(model, constant, "description").isEmpty()) {
                        violations.add(SwaggerRules.at(module, model, constant.getName()) + " is missing a @Schema description");
                    }
                }
                return;
            }
            List<JavaField> documented =
                fields(model).stream().filter(field -> isSerialised(model, field) && !isHidden(model, field)).toList();
            for (JavaField field : documented) {
                String where = SwaggerRules.at(module, model, field.getName());
                if (text(model, field, "description").isEmpty()) {
                    violations.add(where + " is missing a @Schema description");
                }
                if (needsExample(field.getType())) {
                    checkExample(model, field, where);
                }
            }
            model.getRawSuperclass().ifPresent(parent -> reach(parent.getName(), module, at));
            subtypes(model).forEach(subtype -> reach(subtype, module, at));
            for (JavaField field : documented) {
                follow(field.getType(), module, SwaggerRules.at(module, model, field.getName()), new TreeSet<>());
            }
        }

        private void checkExample(JavaClass model, JavaField field, String where) {
            boolean hasExample = text(model, field, "example").isPresent();
            String label = model.getName() + "#" + field.getName();
            if (withoutExample.contains(label)) {
                exemptionsApplied.add(label);
                if (hasExample) {
                    violations.add(where + " is exempt from the example rule but carries a @Schema example");
                }
            } else if (!hasExample) {
                violations.add(where + " is missing a @Schema example");
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

    private static boolean isHidden(JavaClass model, JavaField field) {
        return schemas(model, field).anyMatch(schema -> Annotations.string(schema, "hidden").filter("true"::equals).isPresent());
    }

    private static Stream<JavaAnnotation<?>> schemas(JavaClass model, JavaField field) {
        Stream<JavaAnnotation<?>> onField = field.tryGetAnnotationOfType(SCHEMA).stream().map(annotation -> annotation);
        Stream<JavaAnnotation<?>> onAccessors = accessors(model, field)
            .flatMap(accessor -> accessor.tryGetAnnotationOfType(SCHEMA).stream())
            .map(annotation -> annotation);
        return Stream.concat(onField, onAccessors);
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

    private static Optional<String> text(JavaClass model, JavaField field, String property) {
        return schemas(model, field)
            .flatMap(schema -> Annotations.string(schema, property).stream())
            .filter(value -> !value.isBlank())
            .findFirst();
    }

    private static boolean needsExample(JavaType type) {
        if (type instanceof JavaParameterizedType parameterized) {
            JavaClass raw = parameterized.toErasure();
            List<JavaType> arguments = parameterized.getActualTypeArguments();
            if (isCollection(raw) || raw.getName().equals(OPTIONAL)) {
                return arguments.size() == 1 && needsExample(arguments.get(0));
            }
            return needsExample(raw);
        }
        if (type instanceof JavaGenericArrayType array) {
            return needsExample(array.getComponentType());
        }
        if (type instanceof JavaClass raw) {
            return raw.isArray() ? needsExample(raw.getComponentType()) : isValue(raw);
        }
        return false;
    }

    private static boolean isValue(JavaClass type) {
        String name = type.getName();
        if (NO_EXAMPLE.contains(name) || type.isEnum() || isCollection(type) || isMap(type)) {
            return false;
        }
        return type.isPrimitive() || JDK_PACKAGES.stream().anyMatch(name::startsWith);
    }

    private static boolean isCollection(JavaClass type) {
        return COLLECTIONS.contains(type.getName()) || type.isAssignableTo(COLLECTION);
    }

    private static boolean isMap(JavaClass type) {
        return MAPS.contains(type.getName()) || type.isAssignableTo(MAP);
    }
}
