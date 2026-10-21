package edu.harvard.hms.dbmi.avillach.conventions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityRulesTest {

    private static final String FIXTURES = "edu.harvard.hms.dbmi.avillach.conventions.securityfixtures";
    private static final JavaClasses CONFIGURED = fixtures("configured");
    private static final JavaClasses UNCONFIGURED = fixtures("unconfigured");
    private static final JavaClasses PRE_POST_DISABLED = fixtures("prepostdisabled");
    private static final JavaClasses ROLE_CHECKS = fixtures("rolechecks");
    private static final String KNOWN_CLASS = FIXTURES + ".known.KnownAuthorities";
    private static final Set<String> KNOWN = Set.of("ADMIN", "SUPER_ADMIN", "PRIV_DATA_ADMIN");
    private static final JavaClasses SWAGGER_FIXTURES =
        new ClassFileImporter().importPackages("edu.harvard.hms.dbmi.avillach.conventions.fixtures");

    private static JavaClasses fixtures(String subPackage) {
        return new ClassFileImporter().importPackages(FIXTURES + "." + subPackage);
    }

    private static void assertMentions(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)), fragment + " in " + violations);
    }

    @Test
    void flagsEveryReplacedAnnotationOnClassesAndMethods() {
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
    void flagsGuardsOutsideHandlerMethods() {
        List<String> violations = SecurityRules.preAuthorizeOnlyOnHandlers("fixtures", CONFIGURED);

        assertEquals(2, violations.size(), violations.toString());
        assertMentions(violations, "ClassLevelController carries @PreAuthorize at class level");
        assertMentions(violations, "GuardedService#work carries @PreAuthorize but is not a request handler");
    }

    @Test
    void flagsHandlersThatDeclareNeitherOrBothAndClassLevelDeclarations() {
        List<String> violations = SecurityRules.handlersDeclareAuthorization("fixtures", fixtures("declared"));

        assertEquals(3, violations.size(), violations.toString());
        assertMentions(violations, "ClassLevelPublicController carries @PublicEndpoint at class level");
        assertMentions(violations, "DeclaringController#undeclared carries neither @PreAuthorize nor @PublicEndpoint");
        assertMentions(violations, "DeclaringController#both carries both @PreAuthorize and @PublicEndpoint");
        assertTrue(violations.get(0).startsWith("fixtures :: "), violations.get(0));
    }

    @Test
    void acceptsAGuardOrEitherPublicLevelAndIgnoresNonHandlers() {
        List<String> violations = SecurityRules.handlersDeclareAuthorization("fixtures", fixtures("declared"));

        for (String accepted : List.of("#guarded ", "#anonymous ", "#authenticated ", "#helper ", "ClassLevelPublicController#read ")) {
            assertTrue(violations.stream().noneMatch(v -> v.contains(accepted)), accepted + " in " + violations);
        }
    }

    @Test
    void flagsEveryRoleCheckOnAFrameworkType() {
        List<String> violations = SecurityRules.noRoleChecks("fixtures", ROLE_CHECKS);

        assertEquals(6, violations.size(), violations.toString());
        assertMentions(violations, "UrlRoleRules#chain line 11 calls AuthorizedUrl.hasRole");
        assertMentions(violations, "UrlRoleRules#chain line 11 calls AuthorizedUrl.hasAnyRole");
        assertMentions(violations, "ManagerRoleChecks#admin line 10 calls AuthorityAuthorizationManager.hasRole");
        assertMentions(violations, "ManagerRoleChecks#anyAdmin line 14 calls AuthorityAuthorizationManager.hasAnyRole");
        assertMentions(violations, "RequestRoleChecks#isAdmin line 11 calls HttpServletRequest.isUserInRole");
        assertMentions(violations, "RequestRoleChecks#roleTest line 15 calls HttpServletRequest.isUserInRole");
        assertTrue(violations.get(0).startsWith("fixtures :: "), violations.get(0));
    }

    @Test
    void acceptsAuthorityChecksAndDomainMethodsNamedLikeRoleChecks() {
        List<String> violations = SecurityRules.noRoleChecks("fixtures", ROLE_CHECKS);

        assertTrue(violations.stream().noneMatch(v -> v.contains("AuthorityChecks")), violations.toString());
    }

    @Test
    void preauthorizeStandardFormAcceptsOnlyLiteralAuthorityChecks() {
        List<String> violations = SecurityRules.preAuthorizeNamesAuthorities("fixtures", CONFIGURED, KNOWN);

        assertEquals(6, violations.size(), violations.toString());
        for (String handler : List.of("#role", "#compound", "#twoValuesForOne", "#empty", "#repeated", "#misspelled")) {
            assertMentions(violations, "GuardedController" + handler);
        }
        for (String accepted : List.of("#anyAuthority ", "#anyAuthorityWithoutSpace ", "#oneAuthority ", "#unguarded ")) {
            assertTrue(violations.stream().noneMatch(v -> v.contains(accepted)), accepted + " in " + violations);
        }
    }

    @Test
    void preauthorizeStandardFormNamesTheUnknownAuthority() {
        List<String> violations = SecurityRules.preAuthorizeNamesAuthorities("fixtures", CONFIGURED, KNOWN);

        assertMentions(violations, "GuardedController#misspelled @PreAuthorize(\"hasAnyAuthority('ADMIN', 'SUPER_ADMINN')\") "
            + "names SUPER_ADMINN, which is not a field of the known authority constants");
    }

    @Test
    void readsKnownAuthoritiesFromPublicStaticStringFields() {
        JavaClasses known = fixtures("known");

        assertEquals(KNOWN, SecurityRules.knownAuthorities(Map.of("fixtures", known), KNOWN_CLASS));
    }

    @Test
    void refusesToRunWithoutTheKnownAuthorityConstants() {
        IllegalStateException missing = assertThrows(
            IllegalStateException.class, () -> SecurityRules.knownAuthorities(Map.of("fixtures", CONFIGURED), KNOWN_CLASS)
        );

        assertTrue(missing.getMessage().contains(KNOWN_CLASS), missing.getMessage());
    }

    @Test
    void readsTheAuthoritiesInDeclaredOrder() {
        assertEquals(List.of("ADMIN", "SUPER_ADMIN"), authorities("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')"));
        assertEquals(List.of("SUPER_ADMIN", "ADMIN"), authorities("hasAnyAuthority('SUPER_ADMIN','ADMIN')"));
        assertEquals(List.of("SUPER_ADMIN"), authorities("hasAuthority('SUPER_ADMIN')"));
        assertTrue(SecurityRules.authorities("hasRole('ADMIN')").isEmpty());
        assertTrue(SecurityRules.authorities("hasAnyAuthority('A') or true").isEmpty());
    }

    @Test
    void passesAModuleThatEnablesPrePostMethodSecurity() {
        assertEquals(List.of(), SecurityRules.methodSecurityEnabled("fixtures", CONFIGURED));
    }

    @Test
    void flagsAModuleWithGuardsButNoMethodSecurity() {
        List<String> violations = SecurityRules.methodSecurityEnabled("unconfigured", UNCONFIGURED);

        assertEquals(1, violations.size(), violations.toString());
        assertEquals("unconfigured uses @PreAuthorize but no class declares @EnableMethodSecurity", violations.get(0));
    }

    @Test
    void flagsMethodSecurityWithPrePostSwitchedOff() {
        List<String> violations = SecurityRules.methodSecurityEnabled("prepostdisabled", PRE_POST_DISABLED);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("prePostEnabled"), violations.get(0));
    }

    @Test
    void guardsEnableMethodSecurityIgnoresAModuleWithNoGuards() {
        assertEquals(List.of(), SecurityRules.methodSecurityEnabled("fixtures", SWAGGER_FIXTURES));
    }

    private static List<String> authorities(String expression) {
        return SecurityRules.authorities(expression).orElseThrow();
    }

    @Test
    void flagsDocumentationThatRestatesAGuardedAuthority() {
        JavaClasses restated = fixtures("restated");

        List<String> violations = SwaggerRules.documentationDoesNotRestateAuthorities("fixtures", restated);

        assertEquals(3, violations.size(), violations.toString());
        assertMentions(violations, "RestatingController @Tag description names ADMIN");
        assertMentions(violations, "RestatingController#list @Operation description names ADMIN, SUPER_ADMIN");
        assertMentions(violations, "RestatingController#delete @Operation summary names SUPER_ADMIN");
        assertTrue(violations.stream().noneMatch(v -> v.contains("#create")), violations.toString());
    }

    @Test
    void docsDoNotRestateAuthoritiesIgnoresAModuleWithNoGuards() {
        assertEquals(List.of(), SwaggerRules.documentationDoesNotRestateAuthorities("fixtures", SWAGGER_FIXTURES));
    }
}
