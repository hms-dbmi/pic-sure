package edu.harvard.hms.dbmi.avillach.auth.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class AuthNaming {

    public static class AuthRoleNaming {
        public static final String ROLE_SYSTEM = "ROLE_SYSTEM";
        public static final String ROLE_TOKEN_INTROSPECTION = "ROLE_TOKEN_INTROSPECTION";
        public static final String ROLE_INTROSPECTION_USER = "ROLE_INTROSPECTION_USER";

        public static List<String> allRoles(){
            List<String> roles = new ArrayList<>();
            for (Field field : AuthRoleNaming.class.getFields()){
                roles.add(field.getName());
            }
            return roles;
        }
    }
}
