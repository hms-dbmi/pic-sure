package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * The swagger annotation rules, as pure functions over imported classes. Each returns every violation it
 * finds rather than throwing on the first, so one run reports the whole list instead of turning a 63 item
 * fix into 63 build runs.
 */
public final class SwaggerRules {

    private static final Pattern RESPONSE_CODE = Pattern.compile("\\d{3}|default");

    private SwaggerRules() {}

    /**
     * R1: every controller carries exactly one of Tag or Hidden.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per offending controller
     */
    public static List<String> tagOrHidden(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            boolean tagged = Annotations.has(controller, Annotations.TAG);
            boolean hidden = Annotations.has(controller, Annotations.HIDDEN);
            if (!tagged && !hidden) {
                violations.add(at(module, controller) + " carries neither @Tag nor @Hidden");
            } else if (tagged && hidden) {
                violations.add(at(module, controller) + " carries both @Tag and @Hidden");
            }
        }
        return violations;
    }

    /**
     * R2: a Tag declares a non-blank name and a non-blank description.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per missing or blank property
     */
    public static List<String> tagIsComplete(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            Optional<JavaAnnotation<?>> tag = Annotations.get(controller, Annotations.TAG);
            if (tag.isEmpty()) {
                continue;
            }
            for (String property : List.of("name", "description")) {
                if (Annotations.string(tag.get(), property).filter(value -> !value.isBlank()).isEmpty()) {
                    violations.add(at(module, controller) + " @Tag has a missing or blank " + property);
                }
            }
        }
        return violations;
    }

    /**
     * R3: in a Tag class, every handler method not itself Hidden carries an Operation with a non-blank
     * summary. A Hidden class exempts all of its methods, which is R5.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per offending handler method
     */
    public static List<String> operationHasSummary(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : documentedControllers(classes)) {
            for (JavaMethod method : Controllers.handlerMethods(controller)) {
                if (Annotations.has(method, Annotations.HIDDEN)) {
                    continue;
                }
                Optional<JavaAnnotation<?>> operation = Annotations.get(method, Annotations.OPERATION);
                if (operation.isEmpty()) {
                    violations.add(at(module, controller, method.getName()) + " has no @Operation");
                    continue;
                }
                if (Annotations.string(operation.get(), "summary").filter(value -> !value.isBlank()).isEmpty()) {
                    violations.add(at(module, controller, method.getName()) + " @Operation has a missing or blank summary");
                }
            }
        }
        return violations;
    }

    static List<JavaClass> documentedControllers(JavaClasses classes) {
        return Controllers.of(classes).stream()
            .filter(controller -> !Annotations.has(controller, Annotations.HIDDEN))
            .filter(controller -> Annotations.has(controller, Annotations.TAG))
            .toList();
    }

    static String at(String module, JavaClass controller) {
        return module + " :: " + controller.getSimpleName();
    }

    static String at(String module, JavaClass controller, String method) {
        return at(module, controller) + "#" + method;
    }

    /**
     * R4: every documented handler declares at least one response and at least one 2xx among them, and
     * each declared response carries a three digit code or {@code default} together with a non-blank
     * description. Responses may be declared directly, inside an ApiResponses container, or through the
     * Operation's responses property.
     *
     * <p>The 2xx clause is what stops an endpoint being published as though it can only fail. Several
     * handlers documented a 404 or a 409 and nothing else.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per offending handler method or malformed response
     */
    public static List<String> responsesAreDeclared(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : documentedControllers(classes)) {
            for (JavaMethod method : Controllers.handlerMethods(controller)) {
                if (Annotations.has(method, Annotations.HIDDEN)) {
                    continue;
                }
                String where = at(module, controller, method.getName());
                List<JavaAnnotation<?>> responses = responsesOn(method);
                if (responses.isEmpty()) {
                    violations.add(where + " declares no @ApiResponse");
                    continue;
                }
                boolean success = false;
                for (JavaAnnotation<?> response : responses) {
                    String code = Annotations.string(response, "responseCode").orElse("");
                    if (!RESPONSE_CODE.matcher(code).matches()) {
                        violations.add(where + " @ApiResponse has an invalid responseCode '" + code + "'");
                    } else if (code.startsWith("2")) {
                        success = true;
                    }
                    if (Annotations.string(response, "description").filter(value -> !value.isBlank()).isEmpty()) {
                        violations.add(where + " @ApiResponse " + code + " has a missing or blank description");
                    }
                }
                if (!success) {
                    violations.add(where + " declares no 2xx @ApiResponse");
                }
            }
        }
        return violations;
    }

    private static List<JavaAnnotation<?>> responsesOn(JavaMethod method) {
        List<JavaAnnotation<?>> responses = new ArrayList<>();
        Annotations.get(method, Annotations.API_RESPONSE).ifPresent(responses::add);
        Annotations.get(method, Annotations.API_RESPONSES).ifPresent(container -> responses.addAll(nested(container, "value")));
        Annotations.get(method, Annotations.OPERATION).ifPresent(operation -> responses.addAll(nested(operation, "responses")));
        return responses;
    }

    private static List<JavaAnnotation<?>> nested(JavaAnnotation<?> annotation, String property) {
        Object value = annotation.getProperties().get(property);
        if (!(value instanceof Object[] entries)) {
            return List.of();
        }
        List<JavaAnnotation<?>> nested = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof JavaAnnotation<?> inner) {
                nested.add(inner);
            }
        }
        return nested;
    }

    /**
     * R10: in a module whose handlers carry {@code @PreAuthorize}, no Tag description and no Operation
     * summary or description names one of the authorities those guards require. The shared OpenAPI
     * customizer publishes them from the guard, so prose that repeats them can only drift from it.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per annotation property that names a guarded authority
     */
    public static List<String> documentationDoesNotRestateAuthorities(String module, JavaClasses classes) {
        Set<String> authorities = guardedAuthorities(classes);
        if (authorities.isEmpty()) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            Annotations.get(controller, Annotations.TAG).flatMap(tag -> Annotations.string(tag, "description"))
                .ifPresent(text -> restated(at(module, controller) + " @Tag description", text, authorities, violations));
            for (JavaMethod method : Controllers.handlerMethods(controller)) {
                Optional<JavaAnnotation<?>> operation = Annotations.get(method, Annotations.OPERATION);
                if (operation.isEmpty()) {
                    continue;
                }
                for (String property : List.of("summary", "description")) {
                    String location = at(module, controller, method.getName()) + " @Operation " + property;
                    Annotations.string(operation.get(), property)
                        .ifPresent(text -> restated(location, text, authorities, violations));
                }
            }
        }
        return violations;
    }

    private static Set<String> guardedAuthorities(JavaClasses classes) {
        Set<String> authorities = new TreeSet<>();
        for (JavaClass type : classes) {
            for (JavaMethod method : type.getMethods()) {
                Annotations.get(method, SecurityRules.PRE_AUTHORIZE)
                    .flatMap(annotation -> Annotations.string(annotation, "value"))
                    .flatMap(SecurityRules::authorities)
                    .ifPresent(authorities::addAll);
            }
        }
        return authorities;
    }

    private static void restated(String location, String text, Set<String> authorities, List<String> violations) {
        List<String> named = authorities.stream()
            .filter(authority -> Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(authority) + "(?![A-Za-z0-9_])")
                .matcher(text)
                .find())
            .toList();
        if (!named.isEmpty()) {
            String names = String.join(", ", named);
            violations.add(location + " names " + names + "; the document already publishes it from @PreAuthorize");
        }
    }
}
