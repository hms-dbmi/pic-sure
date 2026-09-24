package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityRulesTest {

    private static final String FIXTURES = "edu.harvard.hms.dbmi.avillach.conventions.securityfixtures";
    private static final JavaClasses CONFIGURED = fixtures("configured");
    private static final JavaClasses UNCONFIGURED = fixtures("unconfigured");
    private static final JavaClasses PRE_POST_DISABLED = fixtures("prepostdisabled");

    private static JavaClasses fixtures(String subPackage) {
        return new ClassFileImporter().importPackages(FIXTURES + "." + subPackage);
    }

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void r6FlagsEveryReplacedAnnotationOnClassesAndMethods() {
        List<String> violations = SecurityRules.noReplacedSecurityAnnotations("fixtures", CONFIGURED);

        assertEquals(5, violations.size(), violations.toString());
        assertMentions(violations, "LegacyController carries @RolesAllowed");
        assertMentions(violations, "LegacyController#rolesAllowed carries @RolesAllowed");
        assertMentions(violations, "LegacyController#secured carries @Secured");
        assertMentions(violations, "LegacyController#permitAll carries @PermitAll");
        assertMentions(violations, "LegacyController#denyAll carries @DenyAll");
        assertTrue(violations.get(0).startsWith("fixtures :: "), violations.get(0));
    }

    @Test
    void r7FlagsGuardsOutsideHandlerMethods() {
        List<String> violations = SecurityRules.preAuthorizeOnlyOnHandlers("fixtures", CONFIGURED);

        assertEquals(2, violations.size(), violations.toString());
        assertMentions(violations, "ClassLevelController carries @PreAuthorize at class level");
        assertMentions(violations, "GuardedService#work carries @PreAuthorize but is not a request handler");
    }

    @Test
    void r8AcceptsOnlyLiteralAuthorityChecks() {
        List<String> violations = SecurityRules.preAuthorizeNamesAuthorities("fixtures", CONFIGURED);

        assertEquals(5, violations.size(), violations.toString());
        for (String handler : List.of("#role", "#compound", "#twoValuesForOne", "#empty", "#repeated")) {
            assertMentions(violations, "GuardedController" + handler);
        }
        for (String accepted : List.of("#anyAuthority ", "#anyAuthorityWithoutSpace ", "#oneAuthority ", "#unguarded ")) {
            assertTrue(violations.stream().noneMatch(v -> v.contains(accepted)), accepted + " in " + violations);
        }
    }

    @Test
    void r8ReadsTheAuthoritiesInDeclaredOrder() {
        assertEquals(List.of("ADMIN", "SUPER_ADMIN"), authorities("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')"));
        assertEquals(List.of("SUPER_ADMIN", "ADMIN"), authorities("hasAnyAuthority('SUPER_ADMIN','ADMIN')"));
        assertEquals(List.of("SUPER_ADMIN"), authorities("hasAuthority('SUPER_ADMIN')"));
        assertTrue(SecurityRules.authorities("hasRole('ADMIN')").isEmpty());
        assertTrue(SecurityRules.authorities("hasAnyAuthority('A') or true").isEmpty());
    }

    @Test
    void r9PassesAModuleThatEnablesPrePostMethodSecurity() {
        assertEquals(List.of(), SecurityRules.methodSecurityEnabled("fixtures", CONFIGURED));
    }

    @Test
    void r9FlagsAModuleWithGuardsButNoMethodSecurity() {
        List<String> violations = SecurityRules.methodSecurityEnabled("unconfigured", UNCONFIGURED);

        assertEquals(1, violations.size(), violations.toString());
        assertEquals("unconfigured uses @PreAuthorize but no class declares @EnableMethodSecurity", violations.get(0));
    }

    @Test
    void r9FlagsMethodSecurityWithPrePostSwitchedOff() {
        List<String> violations = SecurityRules.methodSecurityEnabled("prepostdisabled", PRE_POST_DISABLED);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("prePostEnabled"), violations.get(0));
    }

    @Test
    void r9IgnoresAModuleWithNoGuards() {
        JavaClasses swaggerFixtures =
            new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.fixtures");

        assertEquals(List.of(), SecurityRules.methodSecurityEnabled("fixtures", swaggerFixtures));
    }

    private static List<String> authorities(String expression) {
        return SecurityRules.authorities(expression).orElseThrow();
    }
}
