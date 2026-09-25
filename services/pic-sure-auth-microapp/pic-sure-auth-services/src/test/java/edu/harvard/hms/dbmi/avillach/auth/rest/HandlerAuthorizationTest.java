package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.annotation.security.RolesAllowed;

/**
 * Pins the authorities every guarded PSAMA handler requires. Each handler is called through its method-security proxy with a single
 * authority in the security context: every listed authority must get past method security, and every other one must be denied, so
 * {@code SUPER_ADMIN} never stands in for {@code ADMIN}. The table is the record of what each endpoint requires. A guarded handler missing
 * from it, or a listed handler that lost its guard, fails the build. The full application context runs on the same in-memory H2 settings as
 * {@code OpenApiDocumentTest}, so the two share one cached context.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.datasource.url=jdbc:h2:mem:psama-openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=openapi-test-placeholder-secret", "management.endpoints.web.exposure.include=none"}
)
@AutoConfigureMockMvc
class HandlerAuthorizationTest {

    private static final String ADMIN = "ADMIN";
    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final List<String> CANDIDATES = List.of(ADMIN, SUPER_ADMIN, "PRIV_NOT_AN_ADMIN");

    private static final Map<String, Set<String>> REQUIRED = new TreeMap<>(
        Map.ofEntries(
            Map.entry("AccessRuleController#getAccessRuleById", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("AccessRuleController#getAccessRuleAll", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("AccessRuleController#addAccessRule", Set.of(SUPER_ADMIN)),
            Map.entry("AccessRuleController#updateAccessRule", Set.of(SUPER_ADMIN)),
            Map.entry("AccessRuleController#removeById", Set.of(SUPER_ADMIN)),
            Map.entry("AccessRuleController#getAllTypes", Set.of(SUPER_ADMIN)),
            Map.entry("ApiKeyController#listKeys", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("ApiKeyController#createPlatformKey", Set.of(SUPER_ADMIN)),
            Map.entry("ApiKeyController#revokeKey", Set.of(SUPER_ADMIN)),
            Map.entry("ApplicationController#addApplication", Set.of(SUPER_ADMIN)),
            Map.entry("ApplicationController#updateApplication", Set.of(SUPER_ADMIN)),
            Map.entry("ApplicationController#refreshApplicationToken", Set.of(SUPER_ADMIN)),
            Map.entry("ApplicationController#removeById", Set.of(SUPER_ADMIN)),
            Map.entry("ConnectionWebController#getConnectionById", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("ConnectionWebController#getAllConnections", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("ConnectionWebController#addConnection", Set.of(SUPER_ADMIN)),
            Map.entry("ConnectionWebController#updateConnection", Set.of(SUPER_ADMIN)),
            Map.entry("ConnectionWebController#removeById", Set.of(SUPER_ADMIN)),
            Map.entry("PrivilegeController#getPrivilegeById", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("PrivilegeController#getPrivilegeAll", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("PrivilegeController#addPrivilege", Set.of(SUPER_ADMIN)),
            Map.entry("PrivilegeController#updatePrivilege", Set.of(SUPER_ADMIN)),
            Map.entry("PrivilegeController#removeById", Set.of(SUPER_ADMIN)),
            Map.entry("RoleController#getRoleById", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("RoleController#getRoleAll", Set.of(ADMIN, SUPER_ADMIN)), Map.entry("RoleController#addRole", Set.of(SUPER_ADMIN)),
            Map.entry("RoleController#updateRole", Set.of(SUPER_ADMIN)), Map.entry("RoleController#removeById", Set.of(SUPER_ADMIN)),
            Map.entry("TermsOfServiceController#updateTermsOfService", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("UserController#getUserById", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("UserController#getUserAll", Set.of(ADMIN, SUPER_ADMIN)), Map.entry("UserController#addUser", Set.of(ADMIN)),
            Map.entry("UserController#updateUser", Set.of(ADMIN)),
            Map.entry("UserMetadataMappingWebController#getMappingsForConnection", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("UserMetadataMappingWebController#getAllMappings", Set.of(ADMIN, SUPER_ADMIN)),
            Map.entry("UserMetadataMappingWebController#addMapping", Set.of(SUPER_ADMIN)),
            Map.entry("UserMetadataMappingWebController#updateMapping", Set.of(SUPER_ADMIN)),
            Map.entry("UserMetadataMappingWebController#removeById", Set.of(SUPER_ADMIN))
        )
    );

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> handlerAndAuthority() {
        return REQUIRED.entrySet().stream().flatMap(
            entry -> CANDIDATES.stream().map(authority -> Arguments.of(entry.getKey(), authority, entry.getValue().contains(authority)))
        );
    }

    @ParameterizedTest(name = "{0} with {1} allowed={2}")
    @MethodSource("handlerAndAuthority")
    void handlerAdmitsOnlyItsListedAuthorities(String handler, String authority, boolean allowed) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("caller", null, List.of(new SimpleGrantedAuthority(authority))));

        if (allowed) {
            Throwable outcome = invoke(handler);
            assertThat(outcome instanceof AuthorizationDeniedException).as("%s must admit %s, got %s", handler, authority, outcome)
                .isFalse();
        } else {
            assertThatThrownBy(() -> {
                Throwable outcome = invoke(handler);
                if (outcome != null) {
                    throw outcome;
                }
            }).as("%s must deny %s", handler, authority).isInstanceOf(AuthorizationDeniedException.class);
        }
    }

    @Test
    void everyGuardedHandlerIsInTheTable() {
        Set<String> guarded = new TreeSet<>();
        for (Map.Entry<String, HandlerMethod> entry : handlersByName().entrySet()) {
            MergedAnnotations annotations = MergedAnnotations.from(entry.getValue().getMethod());
            if (annotations.isPresent(PreAuthorize.class) || annotations.isPresent(RolesAllowed.class)) {
                guarded.add(entry.getKey());
            }
        }

        assertThat(guarded).containsExactlyInAnyOrderElementsOf(REQUIRED.keySet());
    }

    private Map<String, HandlerMethod> handlersByName() {
        Map<String, HandlerMethod> byName = new TreeMap<>();
        for (HandlerMethod handlerMethod : handlerMapping.getHandlerMethods().values()) {
            String name = handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
            HandlerMethod previous = byName.put(name, handlerMethod);
            if (previous != null && !previous.getMethod().equals(handlerMethod.getMethod())) {
                throw new IllegalStateException(name + " names more than one handler method");
            }
        }
        return byName;
    }

    private Throwable invoke(String handler) {
        HandlerMethod handlerMethod = handlersByName().get(handler);
        assertThat(handlerMethod).as("no handler named %s", handler).isNotNull();
        Object controller = context.getBean(handlerMethod.getBeanType());
        Method method = handlerMethod.getMethod();
        Object[] arguments = Arrays.stream(method.getParameterTypes()).map(HandlerAuthorizationTest::defaultValue).toArray();
        try {
            method.invoke(controller, arguments);
            return null;
        } catch (InvocationTargetException e) {
            return e.getTargetException();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        return Array.get(Array.newInstance(type, 1), 0);
    }
}
