package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.*;

import java.security.Principal;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>Defines a model for a registered application's behavior and provides application-level privilege management.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Entity(name = "application")
public class Application extends BaseEntity implements Principal {

    @Column(unique = true)
    private String name;
    private String description;
    private String token;
    private String url;
    private boolean enable = true;

    @OneToMany(mappedBy = "application",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private Set<Privilege> privileges;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public Set<Privilege> getPrivileges() {
        return privileges;
    }

    public void setPrivileges(Set<Privilege> privileges) {
        this.privileges = privileges;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * <p>Inner class that returns limited attributes back to an application user.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class ApplicationForDisplay {
        String uuid;
        String name;
        String description;
        String url;
        boolean enable;

        public String getName() {
            return name;
        }

        public ApplicationForDisplay setName(String name) {
            this.name = name;
            return this;
        }

        public String getDescription() {
            return description;
        }

        public ApplicationForDisplay setDescription(String description) {
            this.description = description;
            return this;
        }

        public boolean isEnable() {
            return enable;
        }

        public ApplicationForDisplay setEnable(boolean enable) {
            this.enable = enable;
            return this;
        }

        public String getUuid() {
            return uuid;
        }

        public ApplicationForDisplay setUuid(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public String getUrl() {
            return url;
        }

        public ApplicationForDisplay setUrl(String url) {
            this.url = url;
            return this;
        }
    }
    
    public String toString() {
    		return uuid.toString() + " ___ " + name + " ___ " + description + " ___ " + enable + " ___ " + url + " ___ " + (privileges==null?"NO PRIVILEGES DEFINED" : privileges.stream().map(Privilege::toString).collect(Collectors.joining(",")));
    }
}
