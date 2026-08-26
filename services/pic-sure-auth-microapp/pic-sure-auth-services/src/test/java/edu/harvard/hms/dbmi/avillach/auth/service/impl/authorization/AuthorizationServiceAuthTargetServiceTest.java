package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthorizationServiceAuthTargetServiceTest {

    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        AccessRuleService accessRuleService = mock(AccessRuleService.class);
        SessionService sessionService = mock(SessionService.class);
        RoleService roleService = mock(RoleService.class);
        ConsentBasedAccessRuleEvaluator consentEvaluator = mock(ConsentBasedAccessRuleEvaluator.class);
        UserConsentsRepository userConsentsRepository = mock(UserConsentsRepository.class);

        Role openAccessRole = new Role();
        openAccessRole.setPrivileges(Set.of());
        when(roleService.getRoleByName(MANAGED_OPEN_ACCESS_ROLE_NAME)).thenReturn(openAccessRole);

        authorizationService = new AuthorizationService(
            accessRuleService, sessionService, roleService, consentEvaluator, "fence,okta", userConsentsRepository
        );
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

    @Test
    void anonymousCallerCanReachOpenPathWhenOpenRoleHasNoRules() {
        assertTrue(authorizationService.openAccessRequestIsValid(validationRequest("/hpds/open/v3/query/sync")));
    }

    private static Map<String, Object> validationRequest(String targetService) {
        return Map.of("request", Map.of("Target Service", targetService));
    }
}
