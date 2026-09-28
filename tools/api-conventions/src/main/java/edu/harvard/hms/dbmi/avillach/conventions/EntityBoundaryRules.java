package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * The rules that keep JPA entities off the controller boundary, as pure functions over imported classes.
 * A handler that binds an entity lets the caller write any column Jackson can reach, including ones the
 * endpoint never meant to accept.
 */
public final class EntityBoundaryRules {

    public static final String ENTITY = "jakarta.persistence.Entity";

    private EntityBoundaryRules() {}

    /**
     * R22: no handler parameter's type is, or contains, a class annotated {@code @Entity}. The walk follows
     * generic arguments recursively, array component types, wildcard bounds and type variable bounds, so
     * {@code List<Role>}, {@code Map<String, List<User>>}, {@code User[]} and {@code Optional<User>} all
     * count. Every parameter counts, not only {@code @RequestBody}. Fields are not walked: a request record
     * with an entity-typed field passes. A class is an entity by its annotation, not its package, so a
     * display class nested in an entity passes.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @param reactorEntities the names of entity classes compiled anywhere in the reactor, because an entity
     *     from another module arrives here as an unresolved type that carries no annotations
     * @return one violation per handler parameter whose type contains an entity, naming the parameter's
     *     index and every entity it contains
     */
    public static List<String> noEntityParameters(String module, JavaClasses classes, Set<String> reactorEntities) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                for (JavaParameter parameter : handler.getParameters()) {
                    Set<String> entities = new TreeSet<>();
                    collectEntities(parameter.getType(), reactorEntities, new TreeSet<>(), entities);
                    if (!entities.isEmpty()) {
                        violations.add(
                            SwaggerRules.at(module, controller, handler.getName()) + " parameter " + parameter.getIndex()
                                + " binds entity " + String.join(", ", entities) + "; bind a request record instead"
                        );
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Collects the names of every class annotated {@code @Entity} in the reactor.
     *
     * @param modules every imported module
     * @return the fully qualified names of the entities
     */
    public static Set<String> entityTypes(Map<String, JavaClasses> modules) {
        Set<String> names = new TreeSet<>();
        for (JavaClasses classes : modules.values()) {
            classes.stream().filter(type -> Annotations.has(type, ENTITY)).map(JavaClass::getName).forEach(names::add);
        }
        return names;
    }

    private static void collectEntities(JavaType type, Set<String> reactorEntities, Set<String> visitedVariables, Set<String> found) {
        if (type instanceof JavaParameterizedType parameterized) {
            collectEntities(parameterized.toErasure(), reactorEntities, visitedVariables, found);
            parameterized.getActualTypeArguments().forEach(argument -> collectEntities(argument, reactorEntities, visitedVariables, found));
        } else if (type instanceof JavaGenericArrayType array) {
            collectEntities(array.getComponentType(), reactorEntities, visitedVariables, found);
        } else if (type instanceof JavaWildcardType wildcard) {
            wildcard.getUpperBounds().forEach(bound -> collectEntities(bound, reactorEntities, visitedVariables, found));
            wildcard.getLowerBounds().forEach(bound -> collectEntities(bound, reactorEntities, visitedVariables, found));
        } else if (type instanceof JavaTypeVariable<?> variable) {
            if (visitedVariables.add(variable.getName())) {
                variable.getUpperBounds().forEach(bound -> collectEntities(bound, reactorEntities, visitedVariables, found));
            }
        } else if (type instanceof JavaClass raw) {
            if (raw.isArray()) {
                collectEntities(raw.getComponentType(), reactorEntities, visitedVariables, found);
            } else if (Annotations.has(raw, ENTITY) || reactorEntities.contains(raw.getName())) {
                found.add(raw.getSimpleName());
            }
        }
    }
}
