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
 */
class ApiConventionsTest {

    private static ModuleRegistry registry;
    private static Map<String, JavaClasses> modules;

    @BeforeAll
    static void importReactor() {
        registry = ModuleRegistry.load();
        modules = ReactorModules.discover(Path.of(System.getProperty("reactor.root")));
    }

    @Test
    void everyDocumentedModuleYieldsControllers() {
        report("R0a", RegistryRules.documentedModulesYieldControllers(registry, modules));
    }

    @Test
    void everyModuleWithAControllerIsRegistered() {
        report("R0b", RegistryRules.controllerModulesAreRegistered(registry, modules));
    }

    @Test
    void everyControllerIsTaggedOrHidden() {
        report("R1", overDocumentedModules(SwaggerRules::tagOrHidden));
    }

    @Test
    void everyTagIsComplete() {
        report("R2", overDocumentedModules(SwaggerRules::tagIsComplete));
    }

    @Test
    void everyHandlerHasAnOperationSummary() {
        report("R3", overDocumentedModules(SwaggerRules::operationHasSummary));
    }

    @Test
    void everyHandlerDeclaresItsResponses() {
        report("R4", overDocumentedModules(SwaggerRules::responsesAreDeclared));
    }

    @Test
    void documentationDoesNotRestateGuardedAuthorities() {
        report("R10", overDocumentedModules(SwaggerRules::documentationDoesNotRestateAuthorities));
    }

    @Test
    void noMappingPathEndsInASlash() {
        report("R16", overDocumentedModules(RoutingRules::noTrailingSlash));
    }

    @Test
    void noHandlerUsesAReplacedSecurityAnnotation() {
        report("R6", overAllModules(SecurityRules::noReplacedSecurityAnnotations));
    }

    @Test
    void preAuthorizeSitsOnlyOnHandlers() {
        report("R7", overAllModules(SecurityRules::preAuthorizeOnlyOnHandlers));
    }

    @Test
    void preAuthorizeNamesKnownAuthoritiesInTheStandardForm() {
        Set<String> known = SecurityRules.knownAuthorities(modules, SecurityRules.KNOWN_AUTHORITIES_CLASS);
        report("R8", overAllModules((module, classes) -> SecurityRules.preAuthorizeNamesAuthorities(module, classes, known)));
    }

    @Test
    void everyModuleWithGuardsEnablesMethodSecurity() {
        report("R9", overAllModules(SecurityRules::methodSecurityEnabled));
    }

    @Test
    void noCodeChecksARolePrefix() {
        report("R11", overAllModules(SecurityRules::noRoleChecks));
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
