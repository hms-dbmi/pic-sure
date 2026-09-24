package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;

/**
 * The authorization rules, as pure functions over imported classes. The standard is one annotation in
 * one form: a handler that needs an authority carries {@code @PreAuthorize("hasAnyAuthority('A', 'B')")}
 * or {@code @PreAuthorize("hasAuthority('A')")}, naming roles or privileges as literals.
 *
 * <p>Every granted authority in this reactor is a bare privilege name with no {@code ROLE_} prefix, so
 * {@code hasRole} would match nothing; it is rejected along with every other expression. Holding to one
 * form keeps each requirement readable from the handler, which is what lets the OpenAPI document publish
 * it.
 */
public final class SecurityRules {

    public static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";
    public static final String ENABLE_METHOD_SECURITY =
        "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity";

    /**
     * PSAMA's authority constants. Each public static String field's name is an authority a guard may
     * require, the same list its {@code allRoles()} returns.
     */
    public static final String KNOWN_AUTHORITIES_CLASS = "edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming$AuthRoleNaming";

    private static final Map<String, String> REPLACED = new LinkedHashMap<>();

    static {
        REPLACED.put("jakarta.annotation.security.RolesAllowed", "@RolesAllowed");
        REPLACED.put("javax.annotation.security.RolesAllowed", "@RolesAllowed");
        REPLACED.put("jakarta.annotation.security.PermitAll", "@PermitAll");
        REPLACED.put("javax.annotation.security.PermitAll", "@PermitAll");
        REPLACED.put("jakarta.annotation.security.DenyAll", "@DenyAll");
        REPLACED.put("javax.annotation.security.DenyAll", "@DenyAll");
        REPLACED.put("org.springframework.security.access.annotation.Secured", "@Secured");
    }

    private static final String AUTHORITY = "'([A-Za-z0-9_.:-]+)'";
    private static final Pattern ANY_AUTHORITY = Pattern.compile(
        "hasAnyAuthority\\(\\s*(" + AUTHORITY + "(?:\\s*,\\s*" + AUTHORITY + ")*)\\s*\\)"
    );
    private static final Pattern ONE_AUTHORITY = Pattern.compile("hasAuthority\\(\\s*" + AUTHORITY + "\\s*\\)");
    private static final Pattern QUOTED = Pattern.compile(AUTHORITY);

    private SecurityRules() {}

    /**
     * R6: no class or method carries a JSR-250 security annotation or {@code @Secured}. Method security
     * runs with pre/post support only, so any of them would be silently ignored.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per annotation found
     */
    public static List<String> noReplacedSecurityAnnotations(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : sorted(classes)) {
            for (Map.Entry<String, String> replaced : REPLACED.entrySet()) {
                if (Annotations.has(type, replaced.getKey())) {
                    violations.add(SwaggerRules.at(module, type) + " carries " + replaced.getValue());
                }
            }
            for (JavaMethod method : sortedMethods(type)) {
                for (Map.Entry<String, String> replaced : REPLACED.entrySet()) {
                    if (Annotations.has(method, replaced.getKey())) {
                        String at = SwaggerRules.at(module, type, method.getName());
                        violations.add(at + " carries " + replaced.getValue());
                    }
                }
            }
        }
        return violations;
    }

    /**
     * R7: {@code @PreAuthorize} sits only on controller handler methods. A class-level guard, or one on a
     * service method, is enforced but never reaches the published document.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per misplaced annotation
     */
    public static List<String> preAuthorizeOnlyOnHandlers(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        List<JavaClass> controllers = Controllers.of(classes);
        for (JavaClass type : sorted(classes)) {
            if (Annotations.has(type, PRE_AUTHORIZE)) {
                String at = SwaggerRules.at(module, type);
                violations.add(at + " carries @PreAuthorize at class level; move it to each handler method");
            }
            List<JavaMethod> handlers = controllers.contains(type) ? Controllers.handlerMethods(type) : List.of();
            for (JavaMethod method : sortedMethods(type)) {
                if (Annotations.has(method, PRE_AUTHORIZE) && !handlers.contains(method)) {
                    String at = SwaggerRules.at(module, type, method.getName());
                    violations.add(at + " carries @PreAuthorize but is not a request handler");
                }
            }
        }
        return violations;
    }

    /**
     * R8: every {@code @PreAuthorize} is exactly {@code hasAnyAuthority('A', ...)} or
     * {@code hasAuthority('A')}, with literal, distinct values that are all known authority names. A
     * misspelled name would compile and deny everyone.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @param known the authority names a guard may require
     * @return one violation per expression outside that form or naming an unknown authority
     */
    public static List<String> preAuthorizeNamesAuthorities(String module, JavaClasses classes, Set<String> known) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : sorted(classes)) {
            Annotations.get(type, PRE_AUTHORIZE)
                .ifPresent(annotation -> check(annotation, SwaggerRules.at(module, type), known, violations));
            for (JavaMethod method : sortedMethods(type)) {
                String at = SwaggerRules.at(module, type, method.getName());
                Annotations.get(method, PRE_AUTHORIZE).ifPresent(annotation -> check(annotation, at, known, violations));
            }
        }
        return violations;
    }

    /**
     * R9: a module that uses {@code @PreAuthorize} declares {@code @EnableMethodSecurity} with pre/post
     * support left on. Without it every guard in the module is inert, and each endpoint is open to anyone
     * the filter chain lets through.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return a single violation when the module has guards and nothing enforces them
     */
    public static List<String> methodSecurityEnabled(String module, JavaClasses classes) {
        if (classes.stream().noneMatch(SecurityRules::isGuarded)) {
            return List.of();
        }
        List<JavaAnnotation<?>> enablers = classes.stream()
            .map(type -> Annotations.get(type, ENABLE_METHOD_SECURITY))
            .flatMap(Optional::stream)
            .toList();
        if (enablers.isEmpty()) {
            return List.of(module + " uses @PreAuthorize but no class declares @EnableMethodSecurity");
        }
        boolean prePostOn = enablers.stream()
            .anyMatch(annotation -> !Boolean.FALSE.equals(annotation.getProperties().get("prePostEnabled")));
        return prePostOn
            ? List.of()
            : List.of(module + " uses @PreAuthorize but its @EnableMethodSecurity sets prePostEnabled = false");
    }

    /**
     * Collects the known authority names: the names of the public static String fields of one class,
     * found in whichever module compiled it.
     *
     * @param modules every imported module
     * @param className the fully qualified binary name of the constants class
     * @return the field names
     * @throws IllegalStateException when no module contains the class, because every guard would then
     *     look unknown for the wrong reason
     */
    public static Set<String> knownAuthorities(Map<String, JavaClasses> modules, String className) {
        for (JavaClasses classes : modules.values()) {
            if (classes.contain(className)) {
                Set<String> names = new TreeSet<>();
                for (JavaField field : classes.get(className).getFields()) {
                    Set<JavaModifier> modifiers = field.getModifiers();
                    if (modifiers.containsAll(Set.of(JavaModifier.PUBLIC, JavaModifier.STATIC, JavaModifier.FINAL))
                        && field.getRawType().isEquivalentTo(String.class)) {
                        names.add(field.getName());
                    }
                }
                return names;
            }
        }
        throw new IllegalStateException(className + " is not in any compiled module; build the reactor first");
    }

    /**
     * Reads the authorities a standard {@code @PreAuthorize} expression names.
     *
     * @param expression the annotation's value
     * @return the authorities in declared order, or empty when the expression is not in the standard form
     */
    public static Optional<List<String>> authorities(String expression) {
        String trimmed = expression.strip();
        Matcher any = ANY_AUTHORITY.matcher(trimmed);
        if (any.matches()) {
            List<String> values = new ArrayList<>();
            Matcher quoted = QUOTED.matcher(any.group(1));
            while (quoted.find()) {
                values.add(quoted.group(1));
            }
            return Optional.of(values);
        }
        Matcher one = ONE_AUTHORITY.matcher(trimmed);
        return one.matches() ? Optional.of(List.of(one.group(1))) : Optional.empty();
    }

    private static void check(JavaAnnotation<?> annotation, String location, Set<String> known, List<String> violations) {
        String expression = Annotations.string(annotation, "value").orElse("");
        Optional<List<String>> values = authorities(expression);
        String quoted = location + " @PreAuthorize(\"" + expression + "\")";
        if (values.isEmpty()) {
            violations.add(quoted + " is not hasAnyAuthority('A', ...) or hasAuthority('A') with literal values");
        } else if (new LinkedHashSet<>(values.get()).size() != values.get().size()) {
            violations.add(quoted + " names an authority more than once");
        } else {
            List<String> unknown = values.get().stream().filter(value -> !known.contains(value)).toList();
            if (!unknown.isEmpty()) {
                String names = String.join(", ", unknown);
                violations.add(quoted + " names " + names + ", which is not a field of the known authority constants");
            }
        }
    }

    private static boolean isGuarded(JavaClass type) {
        return Annotations.has(type, PRE_AUTHORIZE)
            || type.getMethods().stream().anyMatch(method -> Annotations.has(method, PRE_AUTHORIZE));
    }

    private static List<JavaClass> sorted(JavaClasses classes) {
        return classes.stream().sorted(Comparator.comparing(JavaClass::getName)).toList();
    }

    private static List<JavaMethod> sortedMethods(JavaClass type) {
        return type.getMethods().stream().sorted(Comparator.comparing(JavaMethod::getName)).toList();
    }
}
