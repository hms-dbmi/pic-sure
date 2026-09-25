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
     * R15: in a module where at least one handler carries {@code @AuditEvent}, every handler carries it.
     * The rule keys on the annotation rather than on an interceptor in the same module, because hpds
     * declares its handlers in one module and reads the annotation from an interceptor in another. A
     * module where no handler carries it is not audited this way and passes.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per handler missing the annotation, empty when the module carries it on
     *     every handler or on none
     */
    public static List<String> auditEventOnEveryHandler(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        int handlers = 0;
        int annotated = 0;
        List<String> missing = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                handlers++;
                if (Annotations.has(handler, AUDIT_EVENT)) {
                    annotated++;
                } else {
                    missing.add(SwaggerRules.at(module, controller, handler.getName()));
                }
            }
        }
        if (annotated == 0) {
            return violations;
        }
        String coverage = annotated + " of " + handlers + " handlers in the module carry it";
        for (String at : missing) {
            violations.add(at + " has no @AuditEvent, so it is audited under a fallback label; " + coverage);
        }
        return violations;
    }
}
