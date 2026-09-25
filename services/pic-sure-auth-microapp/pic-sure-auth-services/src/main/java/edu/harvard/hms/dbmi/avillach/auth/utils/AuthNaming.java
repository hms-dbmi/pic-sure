package edu.harvard.hms.dbmi.avillach.auth.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>Contains all preset PSAMA naming conventions.</p>
 */
public class AuthNaming {

    public static final String LONG_TERM_TOKEN_PREFIX = "LONG_TERM_TOKEN";
    public static final String PSAMA_APPLICATION_TOKEN_PREFIX = "PSAMA_APPLICATION";

    /**
     * <p>Names of the admin privileges that {@code @PreAuthorize} checks on PSAMA's admin endpoints.</p>
     * <p>These are the only authority names a {@code @PreAuthorize} guard in the reactor may use: the api-conventions rules read
     * this class's public field names, so each field's name must equal its value. Add a field here before guarding a handler with a
     * new authority.</p>
     */
    public static class AuthRoleNaming {
        public static final String ADMIN = "ADMIN";
        public static final String SUPER_ADMIN = "SUPER_ADMIN";

        public static List<String> allRoles(){
            List<String> roles = new ArrayList<>();
            for (Field field : AuthRoleNaming.class.getFields()){
                roles.add(field.getName());
            }
            return roles;
        }
    }
}
