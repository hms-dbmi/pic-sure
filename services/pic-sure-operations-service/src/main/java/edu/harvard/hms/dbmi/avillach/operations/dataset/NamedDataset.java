package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import edu.harvard.hms.dbmi.avillach.operations.query.Query;

/** Persistence model for named datasets and their associated queries. */
@Schema(description = "A NamedDataset object containing query, name, user, and archived status.")
@Entity(name = "named_dataset")
@Table(uniqueConstraints = {@UniqueConstraint(name = "unique_queryId_user", columnNames = {"queryId", "\"user\""})})
public class NamedDataset {

    // Hibernate generates a random UUID for a UUID-typed id with a bare @GeneratedValue.
    @Id
    @GeneratedValue
    @Column(columnDefinition = "BINARY(16)")
    private UUID uuid;

    @Schema(description = "The associated Query")
    @OneToOne
    @JoinColumn(name = "queryId")
    private Query query;

    // Quoted because H2 reserves `user`; Hibernate renders the appropriate identifier quoting for each dialect.
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

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

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

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof NamedDataset other)) {
            return false;
        }
        return this.uuid != null && this.uuid.equals(other.uuid);
    }

    @Override
    public String toString() {
        return this.uuid != null ? this.uuid.toString() : super.toString();
    }
}
