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

/**
 * Persistence model for named datasets and their associated queries.
 *
 * <p>{@code queryToVariantUuid} names the general query of the dataset: the {@code query_to_variant} row that the query the dataset points
 * at is one variant of. It is a copy of that query row's own value, written whenever the dataset is created or updated, and it is null
 * while the query row is filed under no general query. The table has a foreign key to {@code query_to_variant}, which is not mapped here:
 * the service reads and writes the id as a plain value.
 */
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

    @Schema(description = "The id of the general query the associated Query is a variant of")
    @Column(name = "query_to_variant_uuid", columnDefinition = "BINARY(16)")
    private UUID queryToVariantUuid;

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

    /**
     * Sets the general query of the dataset.
     *
     * @param queryToVariantUuid the id of the {@code query_to_variant} row, or null to file the dataset under none
     * @return this dataset
     */
    public NamedDataset setQueryToVariantUuid(UUID queryToVariantUuid) {
        this.queryToVariantUuid = queryToVariantUuid;
        return this;
    }

    /**
     * Returns the id of the general query of the dataset.
     *
     * @return the id of the {@code query_to_variant} row, or null for a dataset that is filed under none
     */
    public UUID getQueryToVariantUuid() {
        return queryToVariantUuid;
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
