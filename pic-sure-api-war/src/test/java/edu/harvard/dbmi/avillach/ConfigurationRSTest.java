package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.request.ConfigurationRequest;
import org.junit.Test;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.core.SecurityContext;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConfigurationRSTest {

    private void assertRolesAllowed(Method method, String role) {
        RolesAllowed annotation = method.getAnnotation(RolesAllowed.class);
        assertNotNull("@RolesAllowed missing on " + method.getName(), annotation);
        assertTrue(role + " role missing on " + method.getName(), Arrays.asList(annotation.value()).contains(role));
    }

    @Test
    public void adminEndpoints_requireSuperUserRole() throws NoSuchMethodException {
        assertRolesAllowed(
            ConfigurationRS.class.getMethod("addConfiguration", SecurityContext.class, ConfigurationRequest.class),
            "SuperUser"
        );
        assertRolesAllowed(
            ConfigurationRS.class.getMethod("updateConfiguration", SecurityContext.class, UUID.class, ConfigurationRequest.class),
            "SuperUser"
        );
        assertRolesAllowed(
            ConfigurationRS.class.getMethod("deleteConfiguration", SecurityContext.class, UUID.class),
            "SuperUser"
        );
    }

    @Test
    public void readEndpoints_doNotRequireSuperUserRole() throws NoSuchMethodException {
        assertNotNull(ConfigurationRS.class.getMethod("getConfigurations", SecurityContext.class, String.class));
        assertNotNull(ConfigurationRS.class.getMethod("getConfigurationById", SecurityContext.class, String.class));
    }
}
