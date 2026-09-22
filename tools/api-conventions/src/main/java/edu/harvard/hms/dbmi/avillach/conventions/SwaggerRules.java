package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * The swagger annotation rules, as pure functions over imported classes. Each returns every violation it
 * finds rather than throwing on the first, so one run reports the whole list instead of turning a 63 item
 * fix into 63 build runs.
 */
public final class SwaggerRules {

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

    static String at(String module, JavaClass controller) {
        return module + " :: " + controller.getSimpleName();
    }

    static String at(String module, JavaClass controller, String method) {
        return at(module, controller) + "#" + method;
    }
}
