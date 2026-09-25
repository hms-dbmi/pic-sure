package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * The audit logging rules, as pure functions over imported classes. A service that audits its requests
 * reads the event type and action from {@code @AuditEvent} on the handler that served the request. A
 * handler without the annotation still gets logged, but under a generic fallback label, so its entries
 * cannot be told apart from any other unlabeled request.
 */
public final class AuditRules {

    public static final String AUDIT_EVENT = "edu.harvard.dbmi.avillach.logging.AuditEvent";

    private AuditRules() {}

    /**
     * {@code handler-has-audit-event}: every handler in the module carries {@code @AuditEvent}. There is no
     * exemption, so a module where no handler carries it fails once per handler rather than being skipped. The
     * rule keys on the annotation rather than on an interceptor in the same module, because hpds declares its
     * handlers in one module and reads the annotation from an interceptor in another.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per handler missing the annotation, empty when every handler carries it
     */
    public static List<String> auditEventOnEveryHandler(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                if (!Annotations.has(handler, AUDIT_EVENT)) {
                    violations.add(
                        SwaggerRules.at(module, controller, handler.getName())
                            + " has no @AuditEvent, so it is audited under a fallback label"
                    );
                }
            }
        }
        return violations;
    }
}
