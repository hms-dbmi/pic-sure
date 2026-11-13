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
     * <p>Names of the privileges that {@code @PreAuthorize} checks: PSAMA's admin privileges ({@code ADMIN}, {@code SUPER_ADMIN}) and
     * {@code ASSISTED_SEARCH}, which {@code pic-sure-ai-service}'s chat handlers require, and {@code PIC_SURE_MCP}, which
     * {@code pic-sure-mcp}'s {@code /mcp} access filter requires. PSAMA itself does not guard anything with {@code ASSISTED_SEARCH}
     * or {@code PIC_SURE_MCP}; they are listed here only because the convention rules read this one class for the whole reactor.</p>
     * <p>These are the only authority names a {@code @PreAuthorize} guard in the reactor may use: the api-conventions rules read
     * this class's public field names, so each field's name must equal its value. Add a field here before guarding a handler with a
     * new authority.</p>
     */
    public static class AuthRoleNaming {
        public static final String ADMIN = "ADMIN";
        public static final String SUPER_ADMIN = "SUPER_ADMIN";
        public static final String ASSISTED_SEARCH = "ASSISTED_SEARCH";
        public static final String PIC_SURE_MCP = "PIC_SURE_MCP";

        public static List<String> allRoles(){
            List<String> roles = new ArrayList<>();
            for (Field field : AuthRoleNaming.class.getFields()){
                roles.add(field.getName());
            }
            return roles;
        }
    }
}
