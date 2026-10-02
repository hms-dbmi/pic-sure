package edu.harvard.hms.dbmi.avillach.conventions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the rules to this reactor. Every rule reports its whole list, so one run names every problem
 * rather than the first. The swagger rules cover the modules the registry marks documented; the
 * authorization rules cover every compiled module, because an unenforced guard is a problem wherever it sits.
 * {@code handler-declares-authorization} covers PSAMA alone, the one module whose authorization lives in its own handlers.
 */
class ApiConventionsTest {

    private static final List<String> PSAMA_ONLY = List.of("services/pic-sure-auth-microapp/pic-sure-auth-services");

    private static ModuleRegistry registry;
    private static Map<String, JavaClasses> modules;

    @BeforeAll
    static void importReactor() {
        registry = ModuleRegistry.load();
        modules = ReactorModules.discover(Path.of(System.getProperty("reactor.root")));
    }

    @Test
    void everyDocumentedModuleYieldsControllers() {
        report("documented-module-has-controllers", RegistryRules.documentedModulesYieldControllers(registry, modules));
    }

    @Test
    void everyModuleWithAControllerIsRegistered() {
        report("controller-module-is-registered", RegistryRules.controllerModulesAreRegistered(registry, modules));
    }

    @Test
    void everyControllerIsTaggedOrHidden() {
        report("controller-tagged-or-hidden", overDocumentedModules(SwaggerRules::tagOrHidden));
    }

    @Test
    void everyTagIsComplete() {
        report("tag-is-complete", overDocumentedModules(SwaggerRules::tagIsComplete));
    }

    @Test
    void everyHandlerHasAnOperationSummary() {
        report("operation-has-summary", overDocumentedModules(SwaggerRules::operationHasSummary));
    }

    @Test
    void everyHandlerDeclaresItsResponses() {
        report("responses-are-declared", overDocumentedModules(SwaggerRules::responsesAreDeclared));
    }

    @Test
    void documentationDoesNotRestateGuardedAuthorities() {
        report("docs-do-not-restate-authorities", overDocumentedModules(SwaggerRules::documentationDoesNotRestateAuthorities));
    }

    @Test
    void noHandlerUsesAReplacedSecurityAnnotation() {
        report("no-replaced-security-annotations", overAllModules(SecurityRules::noReplacedSecurityAnnotations));
    }

    @Test
    void preAuthorizeSitsOnlyOnHandlers() {
        report("preauthorize-only-on-handlers", overAllModules(SecurityRules::preAuthorizeOnlyOnHandlers));
    }

    @Test
    void preAuthorizeNamesKnownAuthoritiesInTheStandardForm() {
        Set<String> known = SecurityRules.knownAuthorities(modules, SecurityRules.KNOWN_AUTHORITIES_CLASS);
        report("preauthorize-uses-standard-form", overAllModules((module, classes) -> SecurityRules.preAuthorizeNamesAuthorities(module, classes, known)));
    }

    @Test
    void everyModuleWithGuardsEnablesMethodSecurity() {
        report("guards-enable-method-security", overAllModules(SecurityRules::methodSecurityEnabled));
    }

    @Test
    void noCodeChecksARolePrefix() {
        report("no-role-checks", overAllModules(SecurityRules::noRoleChecks));
    }

    @Test
    void everyHandlerCarriesAnAuditEvent() {
        report("handler-has-audit-event", overAllModules(AuditRules::auditEventOnEveryHandler));
    }

    @Test
    void everyNamedPathVariableAppearsInAMappedPath() {
        report("path-variables-in-template", overAllModules(RoutingRules::pathVariablesAppearInTemplate));
    }

    @Test
    void everyPsamaHandlerDeclaresItsAuthorization() {
        report("handler-declares-authorization", overModules(PSAMA_ONLY, SecurityRules::handlersDeclareAuthorization));
    }

    private static List<String> overModules(List<String> scope, Rule rule) {
        List<String> violations = new ArrayList<>();
        for (String module : scope) {
            JavaClasses classes = modules.get(module);
            if (classes == null) {
                violations.add(module + " is not a compiled module; build the reactor first");
            } else {
                violations.addAll(rule.apply(module, classes));
            }
        }
        return violations;
    }

    private static List<String> overAllModules(Rule rule) {
        List<String> violations = new ArrayList<>();
        modules.forEach((module, classes) -> violations.addAll(rule.apply(module, classes)));
        return violations;
    }

    private static List<String> overDocumentedModules(Rule rule) {
        List<String> violations = new ArrayList<>();
        for (String module : registry.documentedModules()) {
            JavaClasses classes = modules.get(module);
            if (classes != null) {
                violations.addAll(rule.apply(module, classes));
            }
        }
        return violations;
    }

    private static void report(String rule, List<String> violations) {
        assertTrue(
            violations.isEmpty(),
            () -> rule + " failed with " + violations.size() + " violation(s):\n  " + String.join("\n  ", violations)
        );
    }

    @FunctionalInterface
    private interface Rule {
        List<String> apply(String module, JavaClasses classes);
    }
}
