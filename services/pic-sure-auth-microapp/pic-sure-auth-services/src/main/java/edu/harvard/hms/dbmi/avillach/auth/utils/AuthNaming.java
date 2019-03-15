package edu.harvard.hms.dbmi.avillach.auth.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class AuthNaming {

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
