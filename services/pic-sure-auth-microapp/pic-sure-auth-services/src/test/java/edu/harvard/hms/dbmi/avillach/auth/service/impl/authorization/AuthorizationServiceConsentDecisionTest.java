package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class AuthorizationServiceConsentDecisionTest {

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

        routeRule = new AccessRule();
        routeRule.setUuid(UUID.randomUUID());
        routeRule.setName("AR_ALLOW_ROUTE");
        routeRule.setType(AccessRule.TypeNaming.ALL_REG_MATCH);
        when(accessRuleService.evaluateAccessRule(any(), any())).thenReturn(true);
        when(accessRuleService.cachedPreProcessAccessRules(any(), any())).thenReturn(Set.of(routeRule));

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
    }

    @Test
    void consentHolderIsAuthorizedWithoutQueryInspection() {
        givenConsents(Map.of("\\_consents\\", Set.of("phs001062.c1")));
        Map<String, Object> request =
            Map.of("Target Service", "/future/path", "query", Map.of("unexpected", Map.of("expectedResultType", 9999)));

        EvaluateAccessRuleResult result = service(true).isAuthorized(application, request, user, false);

        assertTrue(result.result());
        verify(accessRuleService).evaluateAccessRule(request, routeRule);
    }

    @Test
    void consentlessUserIsDeniedOnNonQueryPath() {
        when(userConsentsRepository.findByUserId(user.getUuid())).thenReturn(null);

        EvaluateAccessRuleResult result = service(true)
            .isAuthorized(application, Map.of("Target Service", "/dictionary/concepts"), user, false);

        assertFalse(result.result());
        assertEquals("User has no consents on file.", result.denialReason().orElseThrow());
    }

    @Test
    void emptyConsentValuesCountAsNoConsents() {
        givenConsents(Map.of("\\_consents\\", Set.of()));

        EvaluateAccessRuleResult result =
            service(true).isAuthorized(application, Map.of("Target Service", "/visualization/auth/distributions"), user, false);

        assertFalse(result.result());
    }

    @Test
    void emptyAccessRuleSetIsDeniedWithCause() {
        givenConsents(Map.of("\\_consents\\", Set.of("phs001062.c1")));
        when(accessRuleService.cachedPreProcessAccessRules(any(), any())).thenReturn(Set.of());

        EvaluateAccessRuleResult result =
            service(true).isAuthorized(application, Map.of("Target Service", "/hpds/auth/v3/query/sync"), user, false);

        assertFalse(result.result());
        assertEquals("No access rule grants this request.", result.denialReason().orElseThrow());
    }

    @Test
    void consentFlagOffSkipsConsentLookup() {
        EvaluateAccessRuleResult result =
            service(false).isAuthorized(application, Map.of("Target Service", "/hpds/auth/v3/query/sync"), user, false);

        assertTrue(result.result());
        verify(userConsentsRepository, never()).findByUserId(any());
    }

    private AuthorizationService service(boolean enabled) {
        return new AuthorizationService(
            accessRuleService, sessionService, roleService, "fence,okta", userConsentsRepository, enabled, false
        );
    }

    private void givenConsents(Map<String, Set<String>> consents) {
        when(userConsentsRepository.findByUserId(user.getUuid()))
            .thenReturn(new UserConsents().setUserId(user.getUuid()).setConsents(consents));
    }
}
