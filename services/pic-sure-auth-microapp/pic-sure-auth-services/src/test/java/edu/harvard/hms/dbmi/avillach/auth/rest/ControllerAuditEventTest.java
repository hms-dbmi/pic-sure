package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

class ControllerAuditEventTest {

    private void assertAuditEvent(Class<?> controller, String methodName, Class<?>[] params, String expectedType, String expectedAction) throws Exception {
        Method method = controller.getMethod(methodName, params);
        AuditEvent event = method.getAnnotation(AuditEvent.class);
        assertNotNull(event, controller.getSimpleName() + "." + methodName + " missing @AuditEvent");
        assertEquals(expectedType, event.type(), controller.getSimpleName() + "." + methodName + " wrong type");
        assertEquals(expectedAction, event.action(), controller.getSimpleName() + "." + methodName + " wrong action");
    }

    @Test
    void authenticationController() throws Exception {
        Class<?> c = AuthenticationController.class;
        // authentication(String idpProvider, Map<String, String> authRequest, HttpServletRequest request)
        assertAuditEvent(c, "authentication", new Class[]{String.class, Map.class, HttpServletRequest.class}, "AUTH", "auth.login");
    }

    @Test
    void tokenController() throws Exception {
        Class<?> c = TokenController.class;
        // inspectToken(Map<String, Object> inputMap, HttpServletRequest request)
        assertAuditEvent(c, "inspectToken", new Class[]{Map.class, HttpServletRequest.class}, "ACCESS", "token.introspect");
        // refreshToken(String authorizationHeader, HttpServletRequest request)
        assertAuditEvent(c, "refreshToken", new Class[]{String.class, HttpServletRequest.class}, "ACCESS", "token.refresh");
    }

    @Test
    void userController() throws Exception {
        Class<?> c = UserController.class;
        // getUserById(String userId, HttpServletRequest request)
        assertAuditEvent(c, "getUserById", new Class[]{String.class, HttpServletRequest.class}, "OTHER", "user.read");
        // getUserAll()
        assertAuditEvent(c, "getUserAll", new Class[]{}, "OTHER", "user.list");
        // addUser(List<User> users, HttpServletRequest request)
        assertAuditEvent(c, "addUser", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "user.modify");
        // updateUser(List<User> users, HttpServletRequest request)
        assertAuditEvent(c, "updateUser", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "user.modify");
        // getCurrentUser(String authorizationHeader, Boolean hasToken)
        assertAuditEvent(c, "getCurrentUser", new Class[]{String.class, Boolean.class}, "ACCESS", "user.profile");
        // getQueryTemplate(String applicationId)
        assertAuditEvent(c, "getQueryTemplate", new Class[]{String.class}, "ACCESS", "user.profile");
        // getQueryTemplate() - no params
        assertAuditEvent(c, "getQueryTemplate", new Class[]{}, "ACCESS", "user.profile");
        // refreshUserToken(HttpHeaders httpHeaders, HttpServletRequest request)
        assertAuditEvent(c, "refreshUserToken", new Class[]{HttpHeaders.class, HttpServletRequest.class}, "ACCESS", "user.profile");
    }

    @Test
    void roleController() throws Exception {
        Class<?> c = RoleController.class;
        // getRoleById(String roleId)
        assertAuditEvent(c, "getRoleById", new Class[]{String.class}, "OTHER", "role.read");
        // getRoleAll()
        assertAuditEvent(c, "getRoleAll", new Class[]{}, "OTHER", "role.list");
        // addRole(List<Role> roles, HttpServletRequest request)
        assertAuditEvent(c, "addRole", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "role.modify");
        // updateRole(List<Role> roles, HttpServletRequest request)
        assertAuditEvent(c, "updateRole", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "role.modify");
        // removeById(String roleId, HttpServletRequest request)
        assertAuditEvent(c, "removeById", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "role.delete");
    }

    @Test
    void privilegeController() throws Exception {
        Class<?> c = PrivilegeController.class;
        // getPrivilegeById(String privilegeId)
        assertAuditEvent(c, "getPrivilegeById", new Class[]{String.class}, "OTHER", "privilege.read");
        // getPrivilegeAll()
        assertAuditEvent(c, "getPrivilegeAll", new Class[]{}, "OTHER", "privilege.list");
        // addPrivilege(List<Privilege> privileges, HttpServletRequest request)
        assertAuditEvent(c, "addPrivilege", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "privilege.modify");
        // updatePrivilege(List<Privilege> privileges, HttpServletRequest request)
        assertAuditEvent(c, "updatePrivilege", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "privilege.modify");
        // removeById(String privilegeId, HttpServletRequest request)
        assertAuditEvent(c, "removeById", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "privilege.delete");
    }

    @Test
    void accessRuleController() throws Exception {
        Class<?> c = AccessRuleController.class;
        // getAccessRuleById(String accessRuleId)
        assertAuditEvent(c, "getAccessRuleById", new Class[]{String.class}, "OTHER", "access_rule.read");
        // getAccessRuleAll()
        assertAuditEvent(c, "getAccessRuleAll", new Class[]{}, "OTHER", "access_rule.list");
        // addAccessRule(List<AccessRule> accessRules, HttpServletRequest request)
        assertAuditEvent(c, "addAccessRule", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "access_rule.modify");
        // updateAccessRule(List<AccessRule> accessRules, HttpServletRequest request)
        assertAuditEvent(c, "updateAccessRule", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "access_rule.modify");
        // removeById(String accessRuleId, HttpServletRequest request)
        assertAuditEvent(c, "removeById", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "access_rule.delete");
        // getAllTypes()
        assertAuditEvent(c, "getAllTypes", new Class[]{}, "OTHER", "access_rule.types");
    }

    @Test
    void applicationController() throws Exception {
        Class<?> c = ApplicationController.class;
        // getApplicationById(String applicationId)
        assertAuditEvent(c, "getApplicationById", new Class[]{String.class}, "OTHER", "application.read");
        // getApplicationAll()
        assertAuditEvent(c, "getApplicationAll", new Class[]{}, "OTHER", "application.list");
        // addApplication(List<Application> applications, HttpServletRequest request)
        assertAuditEvent(c, "addApplication", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "application.modify");
        // updateApplication(List<Application> applications, HttpServletRequest request)
        assertAuditEvent(c, "updateApplication", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "application.modify");
        // refreshApplicationToken(String applicationId, HttpServletRequest request)
        assertAuditEvent(c, "refreshApplicationToken", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "application.token_refresh");
        // removeById(String applicationId, HttpServletRequest request)
        assertAuditEvent(c, "removeById", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "application.delete");
    }

    @Test
    void termsOfServiceController() throws Exception {
        Class<?> c = TermsOfServiceController.class;
        // getLatestTermsOfService()
        assertAuditEvent(c, "getLatestTermsOfService", new Class[]{}, "ACCESS", "tos.view");
        // updateTermsOfService(String html, HttpServletRequest request)
        assertAuditEvent(c, "updateTermsOfService", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "tos.update");
        // hasUserAcceptedTOS()
        assertAuditEvent(c, "hasUserAcceptedTOS", new Class[]{}, "ACCESS", "tos.view");
        // acceptTermsOfService(HttpServletRequest request)
        assertAuditEvent(c, "acceptTermsOfService", new Class[]{HttpServletRequest.class}, "ACCESS", "tos.accept");
    }

    @Test
    void openAccessController() throws Exception {
        Class<?> c = OpenAccessController.class;
        // validate(Map<String, Object> inputMap, HttpServletRequest request)
        assertAuditEvent(c, "validate", new Class[]{Map.class, HttpServletRequest.class}, "ACCESS", "open.validate");
    }

    @Test
    void studyAccessController() throws Exception {
        Class<?> c = StudyAccessController.class;
        // addStudyAccess(String studyIdentifier, HttpServletRequest request)
        assertAuditEvent(c, "addStudyAccess", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "study_access.create");
    }

    @Test
    void connectionWebController() throws Exception {
        Class<?> c = ConnectionWebController.class;
        // getConnectionById(String connectionId)
        assertAuditEvent(c, "getConnectionById", new Class[]{String.class}, "OTHER", "connection.read");
        // getAllConnections()
        assertAuditEvent(c, "getAllConnections", new Class[]{}, "OTHER", "connection.list");
        // addConnection(List<Connection> connections, HttpServletRequest request)
        assertAuditEvent(c, "addConnection", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "connection.modify");
        // updateConnection(List<Connection> connections, HttpServletRequest request)
        assertAuditEvent(c, "updateConnection", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "connection.modify");
        // removeById(String connectionId, HttpServletRequest request)
        assertAuditEvent(c, "removeById", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "connection.delete");
    }

    @Test
    void userMetadataMappingWebController() throws Exception {
        Class<?> c = UserMetadataMappingWebController.class;
        // getMappingsForConnection(String connection)
        assertAuditEvent(c, "getMappingsForConnection", new Class[]{String.class}, "OTHER", "mapping.read");
        // getAllMappings()
        assertAuditEvent(c, "getAllMappings", new Class[]{}, "OTHER", "mapping.list");
        // addMapping(List<UserMetadataMapping> mappings, HttpServletRequest request)
        assertAuditEvent(c, "addMapping", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "mapping.modify");
        // updateMapping(List<UserMetadataMapping> mappings, HttpServletRequest request)
        assertAuditEvent(c, "updateMapping", new Class[]{List.class, HttpServletRequest.class}, "ADMIN", "mapping.modify");
        // removeById(String mappingId, HttpServletRequest request)
        assertAuditEvent(c, "removeById", new Class[]{String.class, HttpServletRequest.class}, "ADMIN", "mapping.delete");
    }

    @Test
    void cacheController() throws Exception {
        Class<?> c = CacheController.class;
        // getCacheNames()
        assertAuditEvent(c, "getCacheNames", new Class[]{}, "OTHER", "cache.list");
        // getCache(String cacheName)
        assertAuditEvent(c, "getCache", new Class[]{String.class}, "OTHER", "cache.read");
    }
}
