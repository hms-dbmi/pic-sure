package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.auth.config.CustomKeyGenerator;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;

class BannerManagementCacheRefreshIntegrationTest {

    private static final String PRE_RESTORE_PATTERN =
        "^/operations/banners(?:/?|/saved/?|/order/?|/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:/?|/publish/?|/disable/?|/archive/?))$";
    private static final String FINAL_PATTERN =
        "^/operations/banners(?:/?|/saved/?|/order/?|/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:/?|/publish/?|/disable/?|/archive/?|/restore/?))$";
    private static final String ARCHIVE_PATH = "/operations/banners/00000000-0000-0000-0000-000000000001/archive";
    private static final String RESTORE_PATH = "/operations/banners/00000000-0000-0000-0000-000000000001/restore";

    @Test
    void processRestartRefreshesStrictAndNonStrictBannerManagementRules() {
        Application picsure = application();
        SyntheticAuthorizationGraph preRestore = graph(picsure, PRE_RESTORE_PATTERN);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            AuthorizationService authorization = context.getBean(AuthorizationService.class);

            assertThat(isAuthorized(authorization, picsure, ARCHIVE_PATH, preRestore.strictAdmin())).isTrue();
            assertThat(isAuthorized(authorization, picsure, ARCHIVE_PATH, preRestore.nonStrictAdmin())).isTrue();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, preRestore.strictAdmin())).isFalse();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, preRestore.nonStrictAdmin())).isFalse();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, preRestore.strictUser())).isFalse();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, preRestore.nonStrictUser())).isFalse();
            assertCached(context, "mergedRulesCache", "strict-admin", "strict-user");
            assertCached(context, "preProcessedAccessRules", "non-strict-admin", "non-strict-user");

            // Model a repository reload after the authorization migration with new entities under the same subjects.
            SyntheticAuthorizationGraph updated = graph(picsure, FINAL_PATTERN);

            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.strictAdmin())).isFalse();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.nonStrictAdmin())).isFalse();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.strictUser())).isFalse();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.nonStrictUser())).isFalse();
        }

        SyntheticAuthorizationGraph updated = graph(picsure, FINAL_PATTERN);
        try (AnnotationConfigApplicationContext restarted = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            AuthorizationService authorization = restarted.getBean(AuthorizationService.class);

            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.strictAdmin())).isTrue();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.nonStrictAdmin())).isTrue();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.strictUser())).isFalse();
            assertThat(isAuthorized(authorization, picsure, RESTORE_PATH, updated.nonStrictUser())).isFalse();
        }
    }

    private void assertCached(AnnotationConfigApplicationContext context, String cacheName, String... subjects) {
        CacheManager cacheManager = context.getBean(CacheManager.class);
        for (String subject : subjects) {
            assertThat(Objects.requireNonNull(cacheManager.getCache(cacheName)).get(subject)).as("%s entry for %s", cacheName, subject)
                .isNotNull();
        }
    }

    private boolean isAuthorized(AuthorizationService authorization, Application application, String path, User user) {
        return authorization.isAuthorized(application, Map.of("Target Service", path), user, false).result();
    }

    private Application application() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("PICSURE");
        return application;
    }

    private SyntheticAuthorizationGraph graph(Application application, String bannerPattern) {
        Privilege bannerManagement = privilege("BANNER_MANAGEMENT", application, routeRule("AR_BANNER_MANAGEMENT_GATEWAY", bannerPattern));
        Privilege ordinaryQuery = privilege("PIC_SURE_ANY_QUERY", application, routeRule("AR_QUERY", "^/query(/.*)?$"));
        return new SyntheticAuthorizationGraph(
            user("strict-admin", "STRICT", bannerManagement), user("non-strict-admin", "NON_STRICT", bannerManagement),
            user("strict-user", "STRICT", ordinaryQuery), user("non-strict-user", "NON_STRICT", ordinaryQuery)
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

    private User user(String subject, String connectionLabel, Privilege privilege) {
        Role role = new Role();
        role.setUuid(UUID.randomUUID());
        role.setName("TEST_ROLE");
        role.setPrivileges(Set.of(privilege));

        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setSubject(subject);
        user.setConnection(new Connection().setLabel(connectionLabel));
        user.setRoles(Set.of(role));
        return user;
    }

    private record SyntheticAuthorizationGraph(User strictAdmin, User nonStrictAdmin, User strictUser, User nonStrictUser) {
    }

    @Configuration
    @EnableCaching(proxyTargetClass = true)
    static class TestConfiguration {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("mergedRulesCache", "preProcessedAccessRules");
        }

        @Bean("customKeyGenerator")
        CustomKeyGenerator customKeyGenerator() {
            return new CustomKeyGenerator();
        }

        @Bean
        AccessRuleRepository accessRuleRepository() {
            return mock(AccessRuleRepository.class);
        }

        @Bean
        AccessRuleService accessRuleService(AccessRuleRepository accessRuleRepository) {
            return new AccessRuleService(accessRuleRepository, "false");
        }

        @Bean
        SessionService sessionService() {
            SessionService sessionService = mock(SessionService.class);
            when(sessionService.isSessionExpired(anyString())).thenReturn(false);
            return sessionService;
        }

        @Bean
        RoleService roleService() {
            return mock(RoleService.class);
        }

        @Bean
        BdcConsentBasedAccessRuleEvaluator consentBasedAccessRuleEvaluator() {
            return mock(BdcConsentBasedAccessRuleEvaluator.class);
        }

        @Bean
        UserConsentsRepository userConsentsRepository() {
            return mock(UserConsentsRepository.class);
        }

        @Bean
        AuthorizationService authorizationService(
            AccessRuleService accessRuleService, SessionService sessionService, RoleService roleService,
            BdcConsentBasedAccessRuleEvaluator consentBasedAccessRuleEvaluator, UserConsentsRepository userConsentsRepository
        ) {
            return new AuthorizationService(
                accessRuleService, sessionService, roleService, consentBasedAccessRuleEvaluator, "STRICT", userConsentsRepository
            );
        }
    }
}
