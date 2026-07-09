package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.Objects;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.entity.Site} (javax/CDI), field mappings unchanged.
 */
@Schema(description = "A site that contains a PIC-SURE installation that we can send data to")
@Table(
    uniqueConstraints = {@UniqueConstraint(name = "unique_code", columnNames = {"code"}),
        @UniqueConstraint(name = "unique_email", columnNames = {"domain"})}
)
@Entity(name = "site")
public class Site {

    // Hibernate 6+: a UUID-typed id with a bare @GeneratedValue (AUTO strategy) is generated via
    // the built-in random UUID generator. This replaces the legacy javax
    // `@GenericGenerator(strategy = "org.hibernate.id.UUIDGenerator")`, which Hibernate 6 removed;
    // both produce a random UUID, so the persisted values and BINARY(16) column are unaffected.
    @Id
    @GeneratedValue
    @Column(columnDefinition = "BINARY(16)")
    private UUID uuid;

    @Schema(description = "The site code. Ex: BCH")
    @Column(length = 15)
    private String code;

    @Schema(description = "The site name. Ex: Boston Children's")
    @Column(length = 255)
    private String name;

    @Schema(description = "The email domain of users for this site. Ex: childrens.harvard.edu")
    @Column(length = 255)
    private String domain;

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof Site other)) {
            return false;
        }
        return this.uuid != null && this.uuid.equals(other.uuid);
    }

    @Override
    public String toString() {
        return this.uuid != null ? this.uuid.toString() : super.toString();
    }
}
