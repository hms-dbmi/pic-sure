package edu.harvard.dbmi.avillach.data.entity;

import java.security.Principal;

import javax.json.Json;

/*
 * This class is used to mirror the User object from the auth DB to maintain schema separation. - nc
 */
public class AuthUser extends BaseEntity implements Principal {
    private String userId;

    private String subject;

    private String roles;

    private String email;

    public String getUserId() {
        return userId;
    }

    public AuthUser setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public String getSubject() {
        return subject;
    }

    public AuthUser setSubject(String subject) {
        this.subject = subject;
        return this;
    }

    public String getRoles() {
        return roles;
    }

    public AuthUser setRoles(String roles) {
        this.roles = roles;
        return this;
    }

    public String getEmail(){
        return email;
    }

    public AuthUser setEmail(String email){
        this.email = email;
        return this;
    }

    @Override // Principal method
    public String getName() {
        return getEmail();
    }

    @Override
    public String toString() {
        return Json.createObjectBuilder()
            .add("userId", userId)
            .add("subject", subject)
            .add("email", email)
            .add("roles", roles)
            .build().toString();
    }
}
