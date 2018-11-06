package edu.harvard.dbmi.avillach.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class PicsureNaming {

    public static class RoleNaming{

        /**
         * please NOTICE:
         * This ROLE_SYSTEM is used across different projects
         * if this ROLE_SYSTEM naming changed, it will affect other projects, like picsure-micro-auth-app,
         * the suggestion is, if you want to change the naming of role_system,
         * please either create a new role named PIC_SURE_SYSTEM_ADMIN
         * or not use this naming in other project (this naming is not designed to use in other projects originally)
         */
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
