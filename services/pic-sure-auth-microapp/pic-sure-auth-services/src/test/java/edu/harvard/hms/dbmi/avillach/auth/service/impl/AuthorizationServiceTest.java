package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.enums.SecurityRoles;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AuthorizationServiceTest {

    @Mock
    private SecurityContext securityContext;

    @InjectMocks
    private AuthorizationService authorizationService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    public void testIsAuthorized_AccessRulePassed() {
        Application application = createTestApplication();
        User user = createTestUser();

        // create access_rule for privilege
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");


        // set application and access_rule for all user privileges
        for (Privilege privilege1 : user.getRoles().iterator().next().getPrivileges()) {
            privilege1.setAccessRules(Collections.singleton(accessRule));
            privilege1.setApplication(application);
        }
        configureUserSecurityContext(user);


        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = authorizationService.isAuthorized(application, requestBody, user);

        assertTrue(result);
    }

    @Test
    public void testIsAuthorized_AccessRuleFailed() {
        Application application = createTestApplication();
        User user = createTestUser();
        configureUserSecurityContext(user);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");

        boolean result = authorizationService.isAuthorized(application, requestBody, user);

        assertFalse(result);
    }

    @Test
    public void testEvaluateAccessRule_GatesPassed() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_GatesFailed() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertFalse(result);
    }

    @Test
    public void testExtractAndCheckRule_Pass() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = authorizationService.extractAndCheckRule(accessRule, requestBody);

        assertTrue(result);
    }

    @Test
    public void testExtractAndCheckRule_Fail() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");

        boolean result = authorizationService.extractAndCheckRule(accessRule, requestBody);

        assertFalse(result);
    }

    @Test
    public void testDecisionMaker_Pass() {
        AccessRule accessRule = new AccessRule();
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        boolean result = authorizationService.decisionMaker(accessRule, "value");

        assertTrue(result);
    }

    @Test
    public void testDecisionMaker_Fail() {
        AccessRule accessRule = new AccessRule();
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        boolean result = authorizationService.decisionMaker(accessRule, "differentValue");

        assertFalse(result);
    }

    @Test
    public void testIsAuthorized_NoRequestBody() {
        Application application = createTestApplication();
        User user = createTestUser();
        configureUserSecurityContext(user);
        application.setPrivileges(user.getPrivilegesByApplication(application));

        boolean result = authorizationService.isAuthorized(application, null, user);

        assertTrue(result);
    }

    @Test
    public void testIsAuthorized_NoPrivileges() {
        Application application = createTestApplication();
        User user = createTestUser();

        user.getRoles().iterator().next().setPrivileges(Collections.emptySet());
        boolean result = authorizationService.isAuthorized(application, new HashMap<>(), user);

        assertFalse(result);
    }

    @Test
    public void testEvaluateAccessRule_NoGates() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_AllGatesPassed_AND() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "value2");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_OneGateFails_AND() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "differentValue");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertFalse(result);
    }

    @Test
    public void testEvaluateAccessRule_OneGatePassed_OR() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));
        accessRule.setGateAnyRelation(true);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "differentValue");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_AllGatesFail_OR() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));
        accessRule.setGateAnyRelation(true);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");
        requestBody.put("test2", "differentValue");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertFalse(result);
    }

    @Test
    public void testEvaluateAccessRule_EvaluateOnlyByGates() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Collections.singletonList(gate1)));
        accessRule.setEvaluateOnlyByGates(true);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_SubAccessRules() {
        AccessRule subAccessRule = new AccessRule();
        subAccessRule.setRule("$.test2");
        subAccessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        subAccessRule.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");
        accessRule.setSubAccessRule(new HashSet<>(Collections.singletonList(subAccessRule)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "value2");

        boolean result = authorizationService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    private Role createTestRole() {
        Role role = new Role();
        role.setName("TEST_ROLE");
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createTestPrivilege()));
        return role;
    }

    private Privilege createTestPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName("TEST_PRIVILEGE");
        privilege.setUuid(UUID.randomUUID());
        return privilege;
    }

    private void configureUserSecurityContext(User user) {
        CustomUserDetails customUserDetails = new CustomUserDetails(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    private Role createSuperAdminRole() {
        Role role = new Role();
        role.setName(SecurityRoles.SUPER_ADMIN.name());
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createSuperAdminPrivilege()));
        return role;
    }

    private Privilege createSuperAdminPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName(AuthNaming.AuthRoleNaming.SUPER_ADMIN);
        privilege.setUuid(UUID.randomUUID());
        return privilege;
    }

    private User createTestUser() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>(Collections.singleton(createTestRole())));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);
        return user;
    }

    private Application createTestApplication() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("TEST_APPLICATION");
        application.setPrivileges(new HashSet<>());
        return application;
    }
}
