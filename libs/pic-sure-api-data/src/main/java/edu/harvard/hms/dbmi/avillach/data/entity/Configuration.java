package edu.harvard.hms.dbmi.avillach.data.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.entity.Configuration} (javax/CDI). The
 * {@code patch(ConfigurationRequest)}/{@code fromRequest(ConfigurationRequest)}/ {@code toString()} convenience methods were intentionally
 * dropped: they depended on the javax {@code ConfigurationRequest} DTO (not ported — out of scope, see task description) and on the JSON-P
 * ({@code javax.json}) API, neither of which is needed by the persistence layer. Downstream services define their own request/response DTOs
 * and mappers.
 */
@Schema(description = "A Configuration object containing name, kind, enabled status, and description.")
@Entity(name = "configuration")
@Table(
    name = "configuration",
    uniqueConstraints = {@UniqueConstraint(name = "unique_uuid", columnNames = {"uuid"}),
        @UniqueConstraint(name = "unique_name_kind", columnNames = {"name", "kind"})}
)
public class Configuration extends BaseEntity {
    @Schema(description = "The configuration name")
    @Column(length = 255)
    private String name;

    @Schema(description = "The configuration kind/type")
    @Column(length = 255)
    private String kind;

    // Quoted: VALUE is a reserved word in H2 (not in MySQL, where legacy leaves it unquoted); see
    // the equivalent note on NamedDataset.user.
    @Schema(description = "The configuration value")
    @Lob
    @Column(name = "\"value\"", columnDefinition = "TEXT")
    private String value;

    @Schema(description = "The configuration description")
    @Column(length = 255)
    private String description;

    @Schema(description = "This configuration is flagged for deletion")
    private Boolean markForDelete = false;

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
}
