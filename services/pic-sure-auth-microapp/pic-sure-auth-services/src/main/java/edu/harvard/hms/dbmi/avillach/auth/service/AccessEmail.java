package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;

import java.util.Set;

public class AccessEmail {

    //TODO: Where to actually store this?
    private String systemInfo = System.getenv("systemName");
    private String documentation = System.getenv("documentation");

    private String username;
    private Set<Role> roles;

    public AccessEmail(User u) {
        this.username = u.getName();
        this.roles = u.getRoles();
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
}
