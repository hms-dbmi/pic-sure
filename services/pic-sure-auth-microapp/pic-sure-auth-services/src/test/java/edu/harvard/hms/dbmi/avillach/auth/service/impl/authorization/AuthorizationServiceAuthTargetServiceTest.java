package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthorizationServiceAuthTargetServiceTest {

    private AccessRuleService accessRuleService;
    private SessionService sessionService;
    private RoleService roleService;
    private UserConsentsRepository userConsentsRepository;

    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        accessRuleService = mock(AccessRuleService.class);
        sessionService = mock(SessionService.class);
        roleService = mock(RoleService.class);
        userConsentsRepository = mock(UserConsentsRepository.class);

        authorizationService = serviceWith(false, Set.of());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"/hpds/auth", "/hpds/auth/", "/hpds/auth/v3/query/sync", "/visualization/auth", "/visualization/auth/distributions"}
    )
    void recognizesAuthBackendPaths(String targetService) {
        assertTrue(AuthorizationService.isAuthTargetService(targetService));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {"", "/hpds/open/v3/query/sync", "/visualization/open/distributions", "/hpds/authentic/v3/query",
            "/visualization/authorized/distributions", "/foo/hpds/auth/v3/query", "/auth/open/validate"}
    )
    void rejectsPathsWithoutAnAuthBackendSegment(String targetService) {
        assertFalse(AuthorizationService.isAuthTargetService(targetService));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hpds/auth/v3/query/sync", "/visualization/auth/distributions"})
    void anonymousCallerCannotReachAuthPathWhenOpenRoleHasNoRules(String targetService) {
        assertFalse(authorizationService.openAccessRequestIsValid(validationRequest(targetService)));
    }

    /**
     * Deny by default applies to open access too. An open-access role with no rules grants no paths, so environments that allow anonymous
     * reads must configure an explicit rule. BDC attaches {@code AR_ALLOW_HPDS_OPEN_INGRESS} to {@code MANAGED_PRIV_OPEN_ACCESS}.
     */
    @Test
    void anonymousCallerIsDeniedOnEveryPathWhenOpenRoleHasNoRules() {
        assertFalse(authorizationService.openAccessRequestIsValid(validationRequest("/hpds/open/v3/query/sync")));
        assertFalse(authorizationService.openAccessRequestIsValid(validationRequest("/visualization/open/distributions")));
    }

    /**
     * With {@code enable.public.access} off, the auth-backend guard denies before any rule is consulted. The open-access role here holds a
     * rule that would pass, so a denial can only have come from the guard.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/hpds/auth/v3/query/sync", "/visualization/auth/distributions"})
    void guardDeniesAuthPathEvenWhenARuleWouldPass(String targetService) {
        AuthorizationService service = serviceWith(false, Set.of(passingRule()));

        assertFalse(service.openAccessRequestIsValid(validationRequest(targetService)));
    }

    /**
     * With {@code enable.public.access} on, the guard steps aside and the open-access rules decide. This is the whole behaviour change: the
     * anonymous Explorer reaching {@code /hpds/auth/**} depends on it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/hpds/auth/v3/query/sync", "/visualization/auth/distributions"})
    void publicAccessLetsAnonymousCallerReachAuthPath(String targetService) {
        AuthorizationService service = serviceWith(true, Set.of(passingRule()));

        assertTrue(service.openAccessRequestIsValid(validationRequest(targetService)));
    }

    /**
     * The flag lifts the guard, it does not grant anything. An environment that turns it on without an open-access rule matching the auth
     * backend still denies, so the flag alone cannot expose data.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/hpds/auth/v3/query/sync", "/visualization/auth/distributions"})
    void publicAccessStillRequiresAnOpenAccessRule(String targetService) {
        AuthorizationService service = serviceWith(true, Set.of());

        assertFalse(service.openAccessRequestIsValid(validationRequest(targetService)));
    }

    /**
     * The flag is scoped to the auth-backend guard. Open-backend paths were never gated by it and must behave the same either way.
     */
    @Test
    void publicAccessDoesNotChangeOpenBackendPaths() {
        AuthorizationService disabled = serviceWith(false, Set.of(passingRule()));
        AuthorizationService enabled = serviceWith(true, Set.of(passingRule()));

        assertTrue(disabled.openAccessRequestIsValid(validationRequest("/hpds/open/v3/query/sync")));
        assertTrue(enabled.openAccessRequestIsValid(validationRequest("/hpds/open/v3/query/sync")));
    }

    private AuthorizationService serviceWith(boolean enablePublicAccess, Set<AccessRule> openAccessRules) {
        Privilege privilege = new Privilege();
        privilege.setAccessRules(openAccessRules);

        Role openAccessRole = new Role();
        openAccessRole.setPrivileges(Set.of(privilege));
        when(roleService.getRoleByName(MANAGED_OPEN_ACCESS_ROLE_NAME)).thenReturn(openAccessRole);

        return new AuthorizationService(
            accessRuleService, sessionService, roleService, "fence,okta", userConsentsRepository, false, enablePublicAccess
        );
    }

    private AccessRule passingRule() {
        AccessRule accessRule = new AccessRule();
        accessRule.setUuid(UUID.randomUUID());
        accessRule.setName("AR_TEST_ALLOW");
        when(accessRuleService.evaluateAccessRule(any(), any())).thenReturn(true);
        return accessRule;
    }

    private static Map<String, Object> validationRequest(String targetService) {
        return Map.of("request", Map.of("Target Service", targetService));
    }
}
