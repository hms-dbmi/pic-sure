package edu.harvard.hms.dbmi.avillach.auth.rest;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;

/**
 * Entities for the admin controller tests, populated the way stored rows are: every identifier set, every secret-bearing member set to a
 * recognizable canary so a test fails if one reaches a response, and every association filled so the nested shapes are exercised.
 */
final class AdminFixtures {

    static final String APPLICATION_TOKEN = "canary.application.bearer.token";
    static final String USER_TOKEN = "canary.user.long.term.token";
    static final String USER_PASSPORT = "canary.user.passport";
    static final String USER_AUTH0_METADATA = "{\"canary\":\"auth0 metadata\"}";
    static final String MERGED_NAME = "canary merged name";
    static final String MERGED_VALUE = "canary merged value";

    private AdminFixtures() {}

    /**
     * An access rule with the working state rule evaluation leaves on it, and the given rules as its gates.
     *
     * @param name the rule name
     * @param gates rules that gate this one; when none are given the rule's gates and sub-rules stay {@code null}, as on a rule just
     *        created
     * @return the access rule
     */
    static AccessRule accessRule(String name, AccessRule... gates) {
        AccessRule rule = new AccessRule();
        rule.setUuid(UUID.randomUUID());
        rule.setName(name);
        rule.setDescription("Allows search requests only");
        rule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        rule.setRule("$.path");
        rule.setValue("/search/");
        rule.setCheckMapKeyOnly(false);
        rule.setCheckMapNode(false);
        rule.setEvaluateOnlyByGates(false);
        rule.setGateAnyRelation(true);
        if (gates.length > 0) {
            rule.setGates(new LinkedHashSet<>(List.of(gates)));
            rule.setSubAccessRule(new LinkedHashSet<>());
        }
        rule.setMergedName(MERGED_NAME);
        rule.getMergedValues().add(MERGED_VALUE);
        return rule;
    }

    /**
     * An application holding a bearer token and no privileges.
     *
     * @param name the application name
     * @return the application
     */
    static Application application(String name) {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName(name);
        application.setDescription("The PIC-SURE API application");
        application.setUrl("/picsureui");
        application.setToken(APPLICATION_TOKEN);
        return application;
    }

    /**
     * A privilege owned by an application and holding the given access rules.
     *
     * @param name the privilege name
     * @param application the owning application, or {@code null}
     * @param accessRules the access rules the privilege holds
     * @return the privilege
     */
    static Privilege privilege(String name, Application application, AccessRule... accessRules) {
        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        privilege.setName(name);
        privilege.setDescription("Access to phs000007 consent group c1");
        privilege.setApplication(application);
        privilege.setAccessRules(new LinkedHashSet<>(List.of(accessRules)));
        return privilege;
    }

    /**
     * A role granting the given privileges.
     *
     * @param name the role name
     * @param privileges the privileges the role grants
     * @return the role
     */
    static Role role(String name, Privilege... privileges) {
        Role role = new Role();
        role.setUuid(UUID.randomUUID());
        role.setName(name);
        role.setDescription("Manages users, roles and privileges");
        role.setPrivileges(new LinkedHashSet<>(List.of(privileges)));
        return role;
    }

    /**
     * A connection with every column set.
     *
     * @param id the connection's business identifier
     * @return the connection
     */
    static Connection connection(String id) {
        Connection connection = new Connection().setId(id).setLabel("FENCE").setSubPrefix(id + "|")
            .setRequiredFields("[{\"label\":\"Email\",\"id\":\"email\"}]");
        connection.setUuid(UUID.randomUUID());
        return connection;
    }

    /**
     * A user who has signed in: subject, token, passport, identity provider metadata and terms acceptance are all set.
     *
     * @param email the user's email
     * @param connection the connection the user signs in through
     * @param roles the roles the user holds
     * @return the user
     */
    static User user(String email, Connection connection, Role... roles) {
        User user = new User().setSubject("fence|" + email).setRoles(new LinkedHashSet<>(List.of(roles))).setConnection(connection)
            .setGeneralMetadata("{\"email\":\"" + email + "\"}").setAuth0metadata(USER_AUTH0_METADATA);
        user.setUuid(UUID.randomUUID());
        user.setEmail(email);
        user.setAcceptedTOS(new Date(1790777100000L));
        user.setToken(USER_TOKEN);
        user.setPassport(USER_PASSPORT);
        return user;
    }
}
