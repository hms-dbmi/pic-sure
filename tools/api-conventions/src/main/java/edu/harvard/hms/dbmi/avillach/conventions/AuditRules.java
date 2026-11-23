package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;

/**
 * The audit logging rules, as pure functions over imported classes. A service that audits its requests
 * reads the event type and action from {@code @AuditEvent} on the handler that served the request. A
 * handler without the annotation still gets logged, but with event type {@code UNLABELED}, so its entries
 * cannot be told apart from any other unlabeled request.
 */
public final class AuditRules {

    public static final String AUDIT_EVENT = "edu.harvard.dbmi.avillach.logging.AuditEvent";
    public static final String INTERNAL_ACTION_PREFIX = "internal_";

    private static final String INTERNAL_SEGMENT = "internal";

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
                            + " has no @AuditEvent, so it is audited as UNLABELED"
                    );
                }
            }
        }
        return violations;
    }

    /**
     * {@code internal-handlers-use-internal-audit-action}: a handler that maps a path with {@code internal} as a
     * whole segment carries an {@code @AuditEvent} action that starts with {@code internal_}, and no other handler
     * does. The paths are the ones {@link RoutingRules#mappedPaths} reads, so a class-level {@code /internal/queries}
     * marks every handler of that class. A segment such as {@code internals} or {@code {internal}} does not count.
     * A handler that maps both an internal path and a path with no such segment fails whichever action it carries,
     * because one action cannot name both.
     *
     * <p>Internal endpoints are called by other services, never through the gateway, and the prefix lets an audit
     * search tell those calls from client calls by action alone. The rule checks the prefix only, not the rest of
     * the action. A handler with no {@code @AuditEvent} is skipped, because {@code handler-has-audit-event} already
     * reports it.
     *
     * @param module the module path, used in the violation text
     * @param classes that module's imported classes
     * @return one violation per internal handler whose action lacks the prefix, and one per handler whose action
     *     carries the prefix while it maps a path with no internal segment
     */
    public static List<String> internalHandlersUseInternalAuditAction(String module, JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : Controllers.of(classes)) {
            for (JavaMethod handler : Controllers.handlerMethods(controller)) {
                Optional<String> action = Annotations.get(handler, AUDIT_EVENT).flatMap(event -> Annotations.string(event, "action"));
                if (action.isEmpty()) {
                    continue;
                }
                List<String> paths = RoutingRules.mappedPaths(controller, handler);
                List<String> internalPaths = paths.stream().filter(AuditRules::hasInternalSegment).toList();
                List<String> otherPaths = paths.stream().filter(path -> !hasInternalSegment(path)).toList();
                boolean prefixed = action.get().startsWith(INTERNAL_ACTION_PREFIX);
                String at = SwaggerRules.at(module, controller, handler.getName());
                if (!prefixed && !internalPaths.isEmpty()) {
                    violations.add(
                        at + " maps " + internalPaths + " under an internal path segment, so its @AuditEvent action '" + action.get()
                            + "' must start with '" + INTERNAL_ACTION_PREFIX + "'"
                    );
                }
                if (prefixed && !otherPaths.isEmpty()) {
                    violations.add(
                        at + " has @AuditEvent action '" + action.get() + "' but maps " + otherPaths + " with no internal path segment"
                    );
                }
            }
        }
        return violations;
    }

    private static boolean hasInternalSegment(String path) {
        return List.of(path.split("/")).contains(INTERNAL_SEGMENT);
    }
}
