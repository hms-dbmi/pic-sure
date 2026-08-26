package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.auth.model.EvaluateAccessRuleResult;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationServiceConsentDecisionTest {

    private static final String CONSENT_PATH = "\\_consents\\";
    private static final String CONSENT = "phs001062.c1";

    private AccessRuleService accessRuleService;
    private UserConsentsRepository userConsentsRepository;
    private SessionService sessionService;
    private RoleService roleService;
    private AccessRule routeRule;
    private User user;
    private Application application;

    @BeforeEach
    void setUp() {
        accessRuleService = mock(AccessRuleService.class);
        userConsentsRepository = mock(UserConsentsRepository.class);
        sessionService = mock(SessionService.class);
        roleService = mock(RoleService.class);
        when(sessionService.isSessionExpired(any())).thenReturn(false);

        routeRule = rule("AR_ALLOW_HPDS_INGRESS", AccessRule.TypeNaming.ALL_REG_MATCH);
        when(accessRuleService.evaluateAccessRule(any(), any())).thenAnswer(invocation -> invocation.getArgument(1) == routeRule);

        application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("TEST_APPLICATION");

        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        privilege.setName("MANAGED_PRIV_AUTH_ACCESS");
        privilege.setApplication(application);
        privilege.setAccessRules(new HashSet<>(Set.of(routeRule)));
        application.setPrivileges(Set.of(privilege));

        Role role = new Role();
        role.setUuid(UUID.randomUUID());
        role.setName("TEST_ROLE");
        role.setPrivileges(Set.of(privilege));

        Connection connection = new Connection();
        connection.setUuid(UUID.randomUUID());
        connection.setLabel("TEST");

        user = new User();
        user.setUuid(UUID.randomUUID());
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@example.org");
        user.setAcceptedTOS(new Date());
        user.setActive(true);
        user.setConnection(connection);
        user.setRoles(Set.of(role));

        when(accessRuleService.cachedPreProcessAccessRules(any(), any())).thenReturn(Set.of(routeRule));
    }

    @Test
    void authHpdsQueryWithConsentsIsScopedAfterRouteAuthorization() {
        givenConsents(Map.of(CONSENT_PATH, Set.of(CONSENT)));

        EvaluateAccessRuleResult result = service(true).isAuthorized(application, queryRequest("/hpds/auth/v3/query/sync"), user, false);

        assertTrue(result.result());
        assertEquals(List.of(new AuthorizationFilter(CONSENT_PATH, Set.of(CONSENT))), result.query().orElseThrow().authorizationFilters());
        verify(accessRuleService).evaluateAccessRule(any(), any());
    }

    @Test
    void authVisualizationQueryWithConsentsIsScopedFromItsPath() {
        givenConsents(Map.of(CONSENT_PATH, Set.of(CONSENT)));

        EvaluateAccessRuleResult result =
            service(true).isAuthorized(application, queryRequest("/visualization/auth/distributions"), user, false);

        assertTrue(result.result());
        assertTrue(result.query().isPresent());
    }

    @Test
    void authQueryOutsideUserStudiesIsScopedInsteadOfRejectedBeforeExecution() {
        givenConsents(Map.of(CONSENT_PATH, Set.of(CONSENT)));

        EvaluateAccessRuleResult result =
            service(true).isAuthorized(application, queryRequest("/hpds/auth/v3/query/sync", "\\phs999999\\variable\\"), user, false);

        assertTrue(result.result());
        assertEquals(List.of(new AuthorizationFilter(CONSENT_PATH, Set.of(CONSENT))), result.query().orElseThrow().authorizationFilters());
    }

    @Test
    void authQueryWithoutConsentsIsDeniedWithCause() {
        when(userConsentsRepository.findByUserId(user.getUuid())).thenReturn(null);

        EvaluateAccessRuleResult result = service(true).isAuthorized(
            application, queryRequest("/hpds/auth/v3/query/sync"), user, false
        );

        assertFalse(result.result());
        assertEquals("User has no consents on file.", result.denialReason().orElseThrow());
    }

    @Test
    void emptyConsentValuesCountAsNoConsents() {
        givenConsents(Map.of(CONSENT_PATH, Set.of()));

        EvaluateAccessRuleResult result =
            service(true).isAuthorized(application, queryRequest("/visualization/auth/distributions"), user, false);

        assertFalse(result.result());
        assertEquals("User has no consents on file.", result.denialReason().orElseThrow());
    }

    @Test
    void authRequestWithoutQueryIsAuthorizedUnmodified() {
        givenConsents(Map.of(CONSENT_PATH, Set.of(CONSENT)));

        EvaluateAccessRuleResult result =
            service(true).isAuthorized(application, Map.of("Target Service", "/hpds/auth/v3/query/status/123"), user, false);

        assertTrue(result.result());
        assertTrue(result.query().isEmpty());
    }

    @Test
    void authRequestWithoutQueryStillRequiresConsents() {
        when(userConsentsRepository.findByUserId(user.getUuid())).thenReturn(null);

        EvaluateAccessRuleResult result = service(true).isAuthorized(
            application, Map.of("Target Service", "/hpds/auth/v3/query/status/123"), user, false
        );

        assertFalse(result.result());
        assertEquals("User has no consents on file.", result.denialReason().orElseThrow());
    }

    @Test
    void disabledConsentAuthorizationPreservesUnscopedBehavior() {
        EvaluateAccessRuleResult result = service(false).isAuthorized(application, queryRequest("/hpds/auth/v3/query/sync"), user, false);

        assertTrue(result.result());
        assertTrue(result.query().isEmpty());
        verify(userConsentsRepository, never()).findByUserId(any());
    }

    @Test
    void authenticatedCallerOnOpenPathIsNotConsentScoped() {
        EvaluateAccessRuleResult result =
            service(true).isAuthorized(application, queryRequest("/visualization/open/distributions"), user, false);

        assertTrue(result.result());
        assertTrue(result.query().isEmpty());
        verify(userConsentsRepository, never()).findByUserId(any());
    }

    @Test
    void queryOnPathWithoutBackendIsDenied() {
        EvaluateAccessRuleResult result = service(true).isAuthorized(application, queryRequest("/future/query"), user, false);

        assertFalse(result.result());
        assertEquals("Query target service does not name an auth or open backend.", result.denialReason().orElseThrow());
    }

    @Test
    void queryOutsideGatewaySplicePositionIsDenied() {
        givenConsents(Map.of(CONSENT_PATH, Set.of(CONSENT)));
        Map<String, Object> request = Map.of(
            "Target Service", "/hpds/auth/v3/query/sync", "query", Map.of("wrapper", Map.of("query", Map.of("expectedResultType", "COUNT")))
        );

        EvaluateAccessRuleResult result = service(true).isAuthorized(application, request, user, false);

        assertFalse(result.result());
        assertEquals("Query is outside the gateway spliceable position.", result.denialReason().orElseThrow());
    }

    private AuthorizationService service(boolean consentAuthorizationEnabled) {
        return new AuthorizationService(
            accessRuleService, sessionService, roleService, new BdcConsentBasedAccessRuleEvaluator(), "fence,okta", userConsentsRepository,
            consentAuthorizationEnabled
        );
    }

    private void givenConsents(Map<String, Set<String>> consents) {
        when(userConsentsRepository.findByUserId(user.getUuid()))
            .thenReturn(new UserConsents().setUserId(user.getUuid()).setConsents(consents));
    }

    private static Map<String, Object> queryRequest(String targetService) {
        return queryRequest(targetService, "\\phs001062\\variable\\");
    }

    private static Map<String, Object> queryRequest(String targetService, String selectedConcept) {
        return Map.of(
            "Target Service", targetService, "query",
            Map.of(
                "query",
                Map.of(
                    "select", List.of(selectedConcept), "authorizationFilters", List.of(), "genomicFilters", List.of(),
                    "expectedResultType", "COUNT"
                )
            )
        );
    }

    private static AccessRule rule(String name, int type) {
        AccessRule rule = new AccessRule();
        rule.setUuid(UUID.randomUUID());
        rule.setName(name);
        rule.setType(type);
        return rule;
    }
}
