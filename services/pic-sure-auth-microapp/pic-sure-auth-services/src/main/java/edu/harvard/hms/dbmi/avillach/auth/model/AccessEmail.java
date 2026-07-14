package edu.harvard.hms.dbmi.avillach.auth.model;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;

import java.util.Set;

/**
 * <p>Provides attributes for generating email notifications.</p>
 */
public class AccessEmail {

    private String systemName = null;
    private String documentation = null;

    private String username;
    private Set<Role> roles;
    private boolean rolesExists;

    public AccessEmail(User u, String systemName) {
        this.username = u.getName();
        this.roles = u.getRoles();
        this.systemName = systemName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public String getDocumentation() {
        return documentation;
    }

    public void setDocumentation(String documentation) {
        this.documentation = documentation;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public boolean isRolesExists() {
        return (roles != null) && (!roles.isEmpty());
    }
}
