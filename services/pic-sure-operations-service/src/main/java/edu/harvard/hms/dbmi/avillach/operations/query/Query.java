package edu.harvard.hms.dbmi.avillach.operations.query;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * Persistence model for the {@code query} table. The nullable {@code resourceId} column is intentionally unmapped because platform services
 * do not read or write the resource registry association.
 *
 * <p>The id is assigned by the caller, never generated: {@link QueryPersistenceService} sets it before the first save. Because an assigned
 * id is already present on an unsaved instance, the entity implements {@link Persistable} and tracks its own new state, so Spring Data
 * inserts a fresh instance and updates a loaded one. Saving an instance with a null id fails.
 *
 * <p>{@code userUuid} is the user the row belongs to. It is written by the insert and left out of every update, so a row never changes
 * owner. Rows stored without an owner keep a null value. {@code consents} holds the consents of the latest run of the query.
 *
 * <p>{@code queryToVariantUuid} names the {@link QueryToVariant} row the query is a variant of, and {@code sample} and {@code resultType}
 * say which variant it is. The first two are written by the insert and left out of every update. The {@code sample} column defaults to
 * false, so an insert that does not name it, as every insert written before the column existed, stores a full result. A general query holds
 * at most one row for each pair of {@code sample} and result type. The variant key does not compare nulls, so it accepts any number of rows
 * that name no general query, and any number of rows of one general query and one {@code sample} value that have no result type. The table
 * also has a foreign key to {@code query_to_variant}, which is not mapped here: the service reads and writes the id as a plain value.
 */
@Entity(name = "query")
@Table(
    uniqueConstraints = {@UniqueConstraint(name = "unique_query_variant", columnNames = {"query_to_variant_uuid", "sample", "result_type"})}
)
public class Query implements Persistable<UUID> {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID uuid;

    @Transient
    private boolean unsaved = true;

    private Instant startTime;

    private Instant readyTime;

    @Convert(converter = PicSureStatusConverter.class)
    private PicSureStatus status;

    private String resourceResultId;

    // Original query request, gzip-compressed.
    @Lob
    @Column(columnDefinition = "BLOB")
    private byte[] query;

    @Column(length = 8192)
    private byte[] metadata;

    private String version;

    @Column(name = "user_uuid", columnDefinition = "BINARY(16)", updatable = false)
    private UUID userUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consents")
    private Map<String, List<String>> consents;

    @Column(name = "query_to_variant_uuid", columnDefinition = "BINARY(16)", updatable = false)
    private UUID queryToVariantUuid;

    @ColumnDefault("false")
    @Column(name = "sample", nullable = false, updatable = false)
    private boolean sample;

    @Column(name = "result_type", length = 64)
    private String resultType;

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    /** Returns the row id, the same value as {@link #getUuid()}. */
    @Override
    public UUID getId() {
        return uuid;
    }

    /**
     * Returns true until this instance has been loaded from or inserted into the database, which tells Spring Data to insert it instead of
     * merging it.
     */
    @Override
    public boolean isNew() {
        return unsaved;
    }

    @PostLoad
    @PostPersist
    void markSaved() {
        this.unsaved = false;
    }

    public String getResourceResultId() {
        return resourceResultId;
    }

    public void setResourceResultId(String resourceResultId) {
        this.resourceResultId = resourceResultId;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getReadyTime() {
        return readyTime;
    }

    public PicSureStatus getStatus() {
        return status;
    }

    public void setReadyTime(Instant readyTime) {
        this.readyTime = readyTime;
    }

    public void setStatus(PicSureStatus status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public Query setVersion(String version) {
        this.version = version;
        return this;
    }

    public String getQuery() {
        if (this.query == null || this.query.length == 0) {
            return "";
        }

        StringBuilder outStr = new StringBuilder();
        try (
            GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(this.query)); BufferedReader bf =
                new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = bf.readLine()) != null) {
                outStr.append(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return outStr.toString();
    }

    public Query setQuery(String queryStr) {
        if (queryStr == null || queryStr.length() == 0) {
            this.query = new byte[0];
            return this;
        }

        try (ByteArrayOutputStream obj = new ByteArrayOutputStream(); GZIPOutputStream gzip = new GZIPOutputStream(obj)) {
            gzip.write(queryStr.getBytes(StandardCharsets.UTF_8));
            gzip.close();
            this.query = obj.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public UUID getUserUuid() {
        return userUuid;
    }

    /**
     * Sets the owner of a row that has not been inserted yet. The column is left out of updates, so a value set on a stored row is never
     * written.
     *
     * @param userUuid the UUID of the user the row belongs to
     */
    public void setUserUuid(UUID userUuid) {
        this.userUuid = userUuid;
    }

    public Map<String, List<String>> getConsents() {
        return consents;
    }

    public void setConsents(Map<String, List<String>> consents) {
        this.consents = consents;
    }

    /**
     * Returns the id of the general query this row is a variant of.
     *
     * @return the id of the {@link QueryToVariant} row, or null for a row that is filed under none
     */
    public UUID getQueryToVariantUuid() {
        return queryToVariantUuid;
    }

    /**
     * Sets the general query of a row that has not been inserted yet. The column is left out of updates, so a value set on a stored row is
     * never written.
     *
     * @param queryToVariantUuid the id of the {@link QueryToVariant} row, or null to file the row under none
     */
    public void setQueryToVariantUuid(UUID queryToVariantUuid) {
        this.queryToVariantUuid = queryToVariantUuid;
    }

    /**
     * Returns whether the query returns a sample of the matching patients.
     *
     * @return true for a sample, false for a full result
     */
    public boolean isSample() {
        return sample;
    }

    /**
     * Sets the sample flag of a row that has not been inserted yet. The column is left out of updates, so a value set on a stored row is
     * never written.
     *
     * @param sample true for a sample, false for a full result
     */
    public void setSample(boolean sample) {
        this.sample = sample;
    }

    /**
     * Returns the result type of the query.
     *
     * @return the result type name, or null when none was stored
     */
    public String getResultType() {
        return resultType;
    }

    /**
     * Sets the result type of the query.
     *
     * @param resultType the result type name, at most 64 characters, or null for none
     */
    public void setResultType(String resultType) {
        this.resultType = resultType;
    }

    public byte[] getMetadata() {
        return metadata;
    }

    public void setMetadata(byte[] metadata) {
        this.metadata = metadata;
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
        if (!(obj instanceof Query other)) {
            return false;
        }
        return this.uuid != null && this.uuid.equals(other.uuid);
    }

    @Override
    public String toString() {
        return this.uuid != null ? this.uuid.toString() : super.toString();
    }
}
