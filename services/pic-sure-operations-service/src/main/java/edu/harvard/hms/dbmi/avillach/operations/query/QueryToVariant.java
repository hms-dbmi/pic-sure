package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Persistence model for the {@code query_to_variant} table: one row for each general query. A general query is a user's query whatever its
 * result type and whether or not it is a sample. Every {@link Query} row that has an owner is one variant of a general query and names it
 * in {@code queryToVariantUuid}.
 *
 * <p>The id is chosen by the caller of the internal save, which derives it from the owner and the query. A row is written once, by
 * {@link QueryToVariantRepository#insertIfAbsent}, and never changed or deleted, so the entity has no setters and is only ever loaded.
 */
@Entity(name = "query_to_variant")
public class QueryToVariant {

    @Id
    @Column(name = "uuid", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID uuid;

    @Column(name = "user_uuid", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID userUuid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Creates an empty general query for the persistence provider. */
    protected QueryToVariant() {}

    /**
     * Returns the id of the general query.
     *
     * @return the id
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * Returns the owner of the general query.
     *
     * @return the UUID of the user every variant of the general query belongs to
     */
    public UUID getUserUuid() {
        return userUuid;
    }

    /**
     * Returns when the general query was first stored.
     *
     * @return the time the row was inserted
     */
    public Instant getCreatedAt() {
        return createdAt;
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
        if (!(obj instanceof QueryToVariant other)) {
            return false;
        }
        return this.uuid != null && this.uuid.equals(other.uuid);
    }

    @Override
    public String toString() {
        return this.uuid != null ? this.uuid.toString() : super.toString();
    }
}
