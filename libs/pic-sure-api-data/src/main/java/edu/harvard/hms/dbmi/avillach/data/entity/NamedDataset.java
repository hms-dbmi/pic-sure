package edu.harvard.hms.dbmi.avillach.data.entity;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.swagger.v3.oas.annotations.media.Schema;

import edu.harvard.hms.dbmi.avillach.data.entity.convert.JsonConverter;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.entity.NamedDataset} (javax/CDI). The JSON-P-based {@code toString()}
 * override was intentionally dropped (not needed by the persistence layer; would otherwise pull in the
 * {@code javax.json}/{@code jakarta.json} API for no functional benefit).
 */
@Schema(description = "A NamedDataset object containing query, name, user, and archived status.")
@Entity(name = "named_dataset")
@Table(uniqueConstraints = {@UniqueConstraint(name = "unique_queryId_user", columnNames = {"queryId", "\"user\""})})
public class NamedDataset extends BaseEntity {
    @Schema(description = "The associated Query")
    @OneToOne
    @JoinColumn(name = "queryId")
    private Query query;

    // Quoted so Hibernate emits a properly escaped identifier: `user` is not a real reserved word
    // in MySQL (legacy's V5 migration backtick-quotes it defensively anyway) but H2 does reserve
    // it, and the quoting is honored per-dialect (backticks on MySQL, double quotes on H2) rather
    // than changing the physical column name.
    @Schema(description = "The user identifier")
    @Column(name = "\"user\"", length = 255)
    private String user;

    @Schema(description = "The name user has assigned to this dataset")
    @Column(length = 255)
    private String name;

    @Schema(description = "The archived state")
    private Boolean archived = false;

    @Schema(description = "A json string object containing override specific values")
    @Column(length = 8192)
    @Convert(converter = JsonConverter.class)
    private Map<String, Object> metadata;

    public NamedDataset setName(String name) {
        this.name = name;
        return this;
    }

    public String getName() {
        return name;
    }

    public NamedDataset setArchived(Boolean archived) {
        this.archived = archived;
        return this;
    }

    public Boolean getArchived() {
        return archived;
    }

    public NamedDataset setQuery(Query query) {
        this.query = query;
        return this;
    }

    public Query getQuery() {
        return query;
    }

    public NamedDataset setUser(String user) {
        this.user = user;
        return this;
    }

    public String getUser() {
        return user;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public NamedDataset setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }
}
