package edu.harvard.hms.dbmi.avillach.conventions.entityfixtures.rest;

import edu.harvard.hms.dbmi.avillach.conventions.entityfixtures.entity.Role;

/**
 * A request record with an entity-typed field, which the rule does not walk.
 *
 * @param name the role name
 * @param template an existing role to copy from
 */
public record RoleRequest(String name, Role template) {}
