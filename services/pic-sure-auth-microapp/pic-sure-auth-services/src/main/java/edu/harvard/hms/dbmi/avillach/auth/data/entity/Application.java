package edu.harvard.hms.dbmi.avillach.auth.data.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.dbmi.avillach.data.entity.BaseEntity;

import javax.persistence.*;

import java.security.Principal;
import java.util.Set;

/**
 * The purpose of this class is to provide an application level privileges management
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Entity(name = "application")
public class Application extends BaseEntity implements Principal {

    @Column(unique = true)
    private String name;
    private String description;
    private String token;
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

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class ApplicationForDisplay {
        String uuid;
        String name;
        String description;
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
    }
}
