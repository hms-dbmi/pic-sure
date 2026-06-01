package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.request.ConfigurationRequest;
import org.junit.Test;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.core.SecurityContext;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConfigurationRSTest {
    private final String SUPER_ADMIN = "SUPER_ADMIN";

    private void assertRolesAllowed(Method method, String role) {
        RolesAllowed annotation = method.getAnnotation(RolesAllowed.class);
        assertNotNull("@RolesAllowed missing on " + method.getName(), annotation);
        assertTrue(role + " role missing on " + method.getName(), Arrays.asList(annotation.value()).contains(role));
    }

    // These unit tests only guard against accidental removal during refactor & they are not an E2E test of the functionality
    @Test
    public void adminEndpoints_requireSuperAdminRole() throws NoSuchMethodException {
        assertRolesAllowed(
            ConfigurationRS.class.getMethod("addConfiguration", SecurityContext.class, ConfigurationRequest.class), SUPER_ADMIN
        );
        assertRolesAllowed(
            ConfigurationRS.class.getMethod("updateConfiguration", SecurityContext.class, UUID.class, ConfigurationRequest.class),
            SUPER_ADMIN
        );
        assertRolesAllowed(ConfigurationRS.class.getMethod("deleteConfiguration", SecurityContext.class, UUID.class), SUPER_ADMIN);
    }

    private void assertPermitAll(Method method) {
        PermitAll permitAll = method.getAnnotation(PermitAll.class);
        assertNotNull(method.getName() + " must have @PermitAll annotation", permitAll);
    }

    @Test
    public void readEndpoints_arePermitAll() throws NoSuchMethodException {
        assertPermitAll(ConfigurationRS.class.getMethod("getConfigurations", SecurityContext.class, String.class));
        assertPermitAll(ConfigurationRS.class.getMethod("getConfigurationById", SecurityContext.class, String.class));
    }
}
