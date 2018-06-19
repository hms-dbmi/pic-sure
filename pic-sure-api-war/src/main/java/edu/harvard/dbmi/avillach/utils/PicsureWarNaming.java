package edu.harvard.dbmi.avillach.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class PicsureWarNaming {

    public static class RoleNaming{
        public static final String ROLE_SYSTEM = "ROLE_SYSTEM";
        public static final String ROLE_RESEARCH = "ROLE_RESEARCH";
        public static final String ROLE_USER = "ROLE_USER";
        public static final String ROLE_TOKEN_INTROSPECTION = "ROLE_TOKEN_INTROSPECTION";
        public static final String ROLE_INTROSPECTION_USER = "ROLE_INTROSPECTION_USER";


        public static List<String> allRoles(){
            List<String> roles = new ArrayList<>();
            for (Field field : RoleNaming.class.getFields()){
                roles.add(field.getName());
            }
            return roles;
        }
    }
}
