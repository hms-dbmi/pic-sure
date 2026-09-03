package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.Objects;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Persistence model for configuration values; request and response mapping is handled outside the entity. */
@Schema(description = "A Configuration object containing name, kind, enabled status, and description.")
@Entity(name = "configuration")
@Table(
    name = "configuration",
    uniqueConstraints = {@UniqueConstraint(name = "unique_uuid", columnNames = {"uuid"}),
        @UniqueConstraint(name = "unique_name_kind", columnNames = {"name", "kind"})}
)
public class Configuration {

    // Hibernate generates a random UUID for a UUID-typed id with a bare @GeneratedValue.
    @Id
    @GeneratedValue
    @Column(columnDefinition = "BINARY(16)")
    private UUID uuid;

    @Schema(description = "The configuration name")
    @Column(length = 255)
    private String name;

    @Schema(description = "The configuration kind/type")
    @Column(length = 255)
    private String kind;

    // Quoted because H2 reserves VALUE; Hibernate renders the appropriate quoting for each dialect.
    @Schema(description = "The configuration value")
    @Lob
    @Column(name = "\"value\"", columnDefinition = "TEXT")
    private String value;

    @Schema(description = "The configuration description")
    @Column(length = 255)
    private String description;

    @Schema(description = "This configuration is flagged for deletion")
    private Boolean markForDelete = false;

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public Configuration setName(String name) {
        this.name = name;
        return this;
    }

    public String getName() {
        return name;
    }

    public Configuration setKind(String kind) {
        this.kind = kind;
        return this;
    }

    public String getKind() {
        return kind;
    }

    public Configuration setValue(String value) {
        this.value = value;
        return this;
    }

    public String getValue() {
        return value;
    }

    public Configuration setDescription(String description) {
        this.description = description;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public Configuration setMarkForDelete(Boolean markForDelete) {
        this.markForDelete = markForDelete;
        return this;
    }

    public Boolean getMarkForDelete() {
        return markForDelete;
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
        if (!(obj instanceof Configuration other)) {
            return false;
        }
        return this.uuid != null && this.uuid.equals(other.uuid);
    }

    @Override
    public String toString() {
        return this.uuid != null ? this.uuid.toString() : super.toString();
    }
}
