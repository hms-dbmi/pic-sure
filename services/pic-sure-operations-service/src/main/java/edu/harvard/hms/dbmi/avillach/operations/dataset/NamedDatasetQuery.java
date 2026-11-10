package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.domain.Persistable;

/**
 * Persistence model for the {@code named_dataset_query} table: one row for each query linked to a named dataset. A dataset holds at most
 * one row for each variant, and a variant is the {@code sample} flag together with the result type.
 *
 * <p>{@code sample} and {@code resultType} are copies of what the stored query body says, taken when the row is written. The body of a
 * query row never changes, so the copies stay true. Rows the migration created from {@code named_dataset.queryId} have a null
 * {@code resultType}, because the migration cannot read the compressed body. {@link NamedDatasetService} fills it in the first time it
 * reads such a row. The variant key does not compare null result types, so the database accepts any number of rows with a null result type
 * for one dataset and one {@code sample} value.
 *
 * <p>The table has a foreign key to {@code named_dataset} that deletes the rows of a deleted dataset, and one to {@code query} that refuses
 * to delete a linked query. Neither is mapped here: the service reads and writes the two ids as plain values.
 *
 * <p>The id is assigned before the first save, so the entity implements {@link Persistable} and tracks its own new state. Saving a new
 * instance always inserts, and an insert that repeats a key fails.
 */
@Entity(name = "named_dataset_query")
@Table(
    uniqueConstraints = {
        @UniqueConstraint(name = "unique_named_dataset_variant", columnNames = {"named_dataset_uuid", "sample", "result_type"})},
    indexes = {@Index(name = "idx_named_dataset_query_query_uuid", columnList = "query_uuid")}
)
public class NamedDatasetQuery implements Persistable<NamedDatasetQuery.Key> {

    /**
     * The primary key of a join row: the named dataset and the query it links.
     *
     * @param namedDatasetUuid the id of the named dataset
     * @param queryUuid the id of the query row
     */
    @Embeddable
    public record Key(
        @Column(name = "named_dataset_uuid", columnDefinition = "BINARY(16)", nullable = false, updatable = false) UUID namedDatasetUuid,
        @Column(name = "query_uuid", columnDefinition = "BINARY(16)", nullable = false, updatable = false) UUID queryUuid
    ) implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Transient
    private boolean unsaved = true;

    @Column(name = "sample", nullable = false, updatable = false)
    private boolean sample;

    @Column(name = "result_type", length = 64)
    private String resultType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Creates an empty join row for the persistence provider. */
    protected NamedDatasetQuery() {}

    /**
     * Creates a join row that has not been saved.
     *
     * @param namedDatasetUuid the id of the named dataset
     * @param queryUuid the id of the query the dataset links
     * @param sample whether the query is a sample query
     * @param resultType the result type the query asks for, or null when it is not known
     * @param createdAt when the query was linked
     */
    public NamedDatasetQuery(UUID namedDatasetUuid, UUID queryUuid, boolean sample, String resultType, Instant createdAt) {
        this.id = new Key(namedDatasetUuid, queryUuid);
        this.sample = sample;
        this.resultType = resultType;
        this.createdAt = createdAt;
    }

    @Override
    public Key getId() {
        return id;
    }

    /** Returns true until this instance has been loaded from or inserted into the database, which tells Spring Data to insert it. */
    @Override
    public boolean isNew() {
        return unsaved;
    }

    @PostLoad
    @PostPersist
    void markSaved() {
        this.unsaved = false;
    }

    public UUID getNamedDatasetUuid() {
        return id.namedDatasetUuid();
    }

    public UUID getQueryUuid() {
        return id.queryUuid();
    }

    public boolean isSample() {
        return sample;
    }

    public String getResultType() {
        return resultType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof NamedDatasetQuery other)) {
            return false;
        }
        return this.id != null && this.id.equals(other.id);
    }
}
