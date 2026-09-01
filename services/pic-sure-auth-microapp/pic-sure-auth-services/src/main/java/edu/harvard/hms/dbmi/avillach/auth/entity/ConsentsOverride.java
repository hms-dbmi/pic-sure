package edu.harvard.hms.dbmi.avillach.auth.entity;

import jakarta.persistence.*;

import java.util.Map;
import java.util.Set;

/**
 * <p>A named set of consents that can be assigned to users in place of the consents they would
 * normally be granted. Linked to users via {@link UserConsentsOverride}.</p>
 */
@Entity(name = "consents_override")
public class ConsentsOverride extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Convert(converter = UserConsents.ConsentsJsonConverter.class)
    private Set<String> consents;

    public String getName() {
        return name;
    }

    public ConsentsOverride setName(String name) {
        this.name = name;
        return this;
    }

    public Set<String> getConsents() {
        return consents;
    }

    public ConsentsOverride setConsents(Set<String> consents) {
        this.consents = consents;
        return this;
    }
}
