package edu.harvard.hms.dbmi.avillach.auth.entity;

import java.util.HashMap;
import java.util.List;

public class UserClaims {

    private String uuid;
    private String name;
    private String email;
    private String sub;

    // RAS, AIM-AHEAD, AUTH0, FENCE, etc
    private String idp;

    // Non-study roles. There are far too many study roles to include them in the claims.
    private List<String> roles;

    // These claims are RAS specific
    private String userid;
    private String era_commons_id;
    private String preferred_username;
    private String user_permission_group;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
    }

    public String getIdp() {
        return idp;
    }

    public void setIdp(String idp) {
        this.idp = idp;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public String getUserid() {
        return userid;
    }

    public void setUserid(String userid) {
        this.userid = userid;
    }

    public String getEra_commons_id() {
        return era_commons_id;
    }

    public void setEra_commons_id(String era_commons_id) {
        this.era_commons_id = era_commons_id;
    }

    public String getPreferred_username() {
        return preferred_username;
    }

    public void setPreferred_username(String preferred_username) {
        this.preferred_username = preferred_username;
    }

    public String getUser_permission_group() {
        return user_permission_group;
    }

    public void setUser_permission_group(String user_permission_group) {
        this.user_permission_group = user_permission_group;
    }
    
    public HashMap<String, Object> toHashMap() {
        HashMap<String, Object> map = new HashMap<>();
        if (uuid != null) map.put("uuid", uuid);
        if (name != null) map.put("name", name);
        if (email != null) map.put("email", email);
        if (sub != null) map.put("sub", sub);
        if (idp != null) map.put("idp", idp);
        if (roles != null) map.put("roles", roles);
        if (userid != null) map.put("userid", userid);
        if (era_commons_id != null) map.put("era_commons_id", era_commons_id);
        if (preferred_username != null) map.put("preferred_username", preferred_username);
        if (user_permission_group != null) map.put("user_permission_group", user_permission_group);
        return map;
    }
}
