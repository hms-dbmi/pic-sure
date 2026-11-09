package edu.harvard.hms.dbmi.avillach.operations.query;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * Persistence model for the {@code query} table. The nullable {@code resourceId} column is intentionally unmapped because platform services
 * do not read or write the resource registry association.
 *
 * <p>The id is assigned by the caller, never generated: {@link QueryPersistenceService} sets it before the first save. Because an assigned
 * id is already present on an unsaved instance, the entity implements {@link Persistable} and tracks its own new state, so Spring Data
 * inserts a fresh instance and updates a loaded one. Saving an instance with a null id fails.
 */
@Entity(name = "query")
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
