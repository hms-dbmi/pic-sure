package edu.harvard.hms.dbmi.avillach.auth.model.response;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;

/**
 * Every admin response record maps {@code null} to {@code null}, both for a single entity and for a collection of them.
 */
class ResponseNullContractTest {

    @Test
    void connectionMapsNullToNull() {
        assertNull(ConnectionResponse.from(null));
        assertNull(ConnectionResponse.fromAll(null));
    }

    @Test
    void userMetadataMappingMapsNullToNull() {
        assertNull(UserMetadataMappingResponse.from((UserMetadataMapping) null));
        assertNull(UserMetadataMappingResponse.fromAll(null));
    }

    @Test
    void roleMapsNullToNull() {
        assertNull(RoleResponse.from((Role) null));
        assertNull(RoleResponse.fromAll(null));
    }

    @Test
    void privilegeMapsNullToNull() {
        assertNull(PrivilegeResponse.from((Privilege) null));
        assertNull(PrivilegeResponse.fromAll(null));
    }

    @Test
    void accessRuleMapsNullToNull() {
        assertNull(AccessRuleResponse.from((AccessRule) null));
        assertNull(AccessRuleResponse.fromAll(null));
    }

    @Test
    void userMapsNullToNull() {
        assertNull(UserResponse.from((User) null));
        assertNull(UserResponse.fromAll(null));
    }

    @Test
    void applicationMapsNullToNull() {
        assertNull(ApplicationResponse.from((Application) null));
        assertNull(ApplicationResponse.withPrivileges((Application) null));
        assertNull(ApplicationResponse.ownerOfPrivilege((Application) null));
    }
}
