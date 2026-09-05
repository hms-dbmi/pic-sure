package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;

class BannerManagementAuthorizationTest {

    private static final RouteFixture ROUTES = loadRouteFixture();

    private Application picsure;
    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        picsure = new Application();
        picsure.setUuid(UUID.randomUUID());
        picsure.setName("PICSURE");

        SessionService sessions = mock(SessionService.class);
        when(sessions.isSessionExpired(anyString())).thenReturn(false);
        authorizationService = new AuthorizationService(
            new AccessRuleService(mock(AccessRuleRepository.class), "false"), sessions, mock(RoleService.class),
            "OKTA,FENCE,OPEN,RAS", mock(UserConsentsRepository.class), false, false
        );
    }

    @Test
    void bannerManagementRequiresTheApplicationScopedAdminPrivilege() {
        AccessRule bannerRoute = routeRule("AR_BANNER_MANAGEMENT_GATEWAY", ROUTES.pattern());
        Privilege globalAdmin = privilege("ADMIN", null, bannerRoute);
        Privilege scopedBannerManagement = privilege("BANNER_MANAGEMENT", picsure, bannerRoute);
        Privilege ordinaryQuery = privilege("PIC_SURE_ANY_QUERY", picsure, routeRule("AR_QUERY", "^/query(/.*)?$"));
        Map<String, Object> request = Map.of("Target Service", "/operations/banners");

        assertThat(authorizationService.isAuthorized(picsure, request, user(globalAdmin, scopedBannerManagement), false).result()).isTrue();
        assertThat(authorizationService.isAuthorized(picsure, request, user(ordinaryQuery), false).result()).isFalse();
        assertThat(authorizationService.isAuthorized(picsure, request, user(globalAdmin), false).result()).isFalse();
        assertThat(
            authorizationService.isAuthorized(
                picsure, Map.of("Target Service", "/operations/banners/00000000-0000-0000-0000-000000000001/publish"),
                user(scopedBannerManagement), false
            ).result()
        ).isTrue();
        assertThat(
            authorizationService.isAuthorized(
                picsure, Map.of("Target Service", "/operations/banners/00000000-0000-0000-0000-000000000001/disable"),
                user(scopedBannerManagement), false
            ).result()
        ).isTrue();
        assertThat(
            authorizationService.isAuthorized(
                picsure, Map.of("Target Service", "/operations/banners/00000000-0000-0000-0000-000000000001/archive"),
                user(scopedBannerManagement), false
            ).result()
        ).isTrue();
        assertThat(
            authorizationService.isAuthorized(
                picsure, Map.of("Target Service", "/operations/banners/00000000-0000-0000-0000-000000000001/archive"),
                user(globalAdmin), false
            ).result()
        ).isFalse();
        assertThat(
            authorizationService.isAuthorized(
                picsure, Map.of("Target Service", "/operations/banners/00000000-0000-0000-0000-000000000001/restore"),
                user(scopedBannerManagement), false
            ).result()
        ).isTrue();
        assertThat(
            authorizationService.isAuthorized(
                picsure, Map.of("Target Service", "/operations/banners/00000000-0000-0000-0000-000000000001/restore"),
                user(globalAdmin), false
            ).result()
        ).isFalse();
    }

    @ParameterizedTest
    @MethodSource("allowedManagementPaths")
    void bannerManagementPrivilegeAllowsOnlyTheManagementRoutes(String path) {
        AccessRule bannerRoute = routeRule("AR_BANNER_MANAGEMENT_GATEWAY", ROUTES.pattern());
        Privilege scopedBannerManagement = privilege("BANNER_MANAGEMENT", picsure, bannerRoute);

        assertThat(authorizationService.isAuthorized(picsure, Map.of("Target Service", path), user(scopedBannerManagement), false).result())
            .isTrue();
    }

    @ParameterizedTest
    @MethodSource("rejectedManagementPaths")
    void bannerManagementPrivilegeRejectsPublicAndUnrecognizedDescendants(String path) {
        AccessRule bannerRoute = routeRule("AR_BANNER_MANAGEMENT_GATEWAY", ROUTES.pattern());
        Privilege scopedBannerManagement = privilege("BANNER_MANAGEMENT", picsure, bannerRoute);

        assertThat(authorizationService.isAuthorized(picsure, Map.of("Target Service", path), user(scopedBannerManagement), false).result())
            .isFalse();
    }

    private static Stream<String> allowedManagementPaths() {
        return ROUTES.allowed().stream();
    }

    private static Stream<String> rejectedManagementPaths() {
        return Stream.concat(
            ROUTES.rejected().stream(), Stream.of("/operations/banners/active/v2", "/operations/banners/active/v2/")
        );
    }

    private static RouteFixture loadRouteFixture() {
        Properties properties = new Properties();
        try (InputStream fixture = BannerManagementAuthorizationTest.class.getResourceAsStream("/banner-management-routes.properties")) {
            if (fixture == null) {
                throw new IllegalStateException("Missing banner-management-routes.properties");
            }
            properties.load(fixture);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load banner management route fixture", e);
        }
        return new RouteFixture(
            properties.getProperty("pattern"), List.of(properties.getProperty("allowed").split(",")),
            List.of(properties.getProperty("rejected").split(","))
        );
    }

    private AccessRule routeRule(String name, String value) {
        AccessRule rule = new AccessRule();
        rule.setUuid(UUID.randomUUID());
        rule.setName(name);
        rule.setRule("$.['Target Service']");
        rule.setType(AccessRule.TypeNaming.ALL_REG_MATCH);
        rule.setValue(value);
        return rule;
    }

    private Privilege privilege(String name, Application application, AccessRule rule) {
        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        privilege.setName(name);
        privilege.setApplication(application);
        privilege.setAccessRules(Set.of(rule));
        return privilege;
    }

    private User user(Privilege... privileges) {
        Role role = new Role();
        role.setUuid(UUID.randomUUID());
        role.setName("TEST_ROLE");
        role.setPrivileges(Set.of(privileges));

        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setSubject(UUID.randomUUID().toString());
        user.setRoles(Set.of(role));
        return user;
    }

    private record RouteFixture(String pattern, List<String> allowed, List<String> rejected) {
    }
}
