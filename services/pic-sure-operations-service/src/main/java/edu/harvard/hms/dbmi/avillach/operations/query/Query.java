package edu.harvard.hms.dbmi.avillach.operations.query;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.entity.Query} (javax/CDI). The {@code resourceId} FK /
 * {@code @ManyToOne Resource resource} association is intentionally dropped: the {@code resource} registry table/entity is being removed in
 * this migration, the {@code query.resourceId} column is nullable (see legacy V1__CREATE_PICSURE_INITIAL.sql), and the platform services do
 * not read or write it (new rows simply leave it NULL until the column itself is dropped).
 */
@Entity(name = "query")
public class Query {

    // Hibernate 6+: a UUID-typed id with a bare @GeneratedValue (AUTO strategy) is generated via
    // the built-in random UUID generator. This replaces the legacy javax
    // `@GenericGenerator(strategy = "org.hibernate.id.UUIDGenerator")`, which Hibernate 6 removed;
    // both produce a random UUID, so the persisted values and BINARY(16) column are unaffected.
    @Id
    @GeneratedValue
    @Column(columnDefinition = "BINARY(16)")
    private UUID uuid;

    private Date startTime;

    private Date readyTime;

    // Resource is responsible for mapping internal status to picsurestatus.
    // Persisted as the enum NAME, not its ordinal. The legacy entity carried no @Enumerated annotation at all, so JPA
    // defaulted to ORDINAL and the column was an int whose meaning was the DECLARATION ORDER of PicSureStatus --
    // reordering or inserting a constant silently rewrote every stored row. V9__ALTER_QUERY_STATUS_TO_STRING.sql widens
    // the column to VARCHAR(32) and rewrites the four legacy ordinals (0..3) to their names, so this mapping and the
    // schema move together; the length here matches that migration.
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
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

    public String getResourceResultId() {
        return resourceResultId;
    }

    public void setResourceResultId(String resourceResultId) {
        this.resourceResultId = resourceResultId;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getStartTime() {
        return startTime;
    }

    public Date getReadyTime() {
        return readyTime;
    }

    public PicSureStatus getStatus() {
        return status;
    }

    public void setReadyTime(Date readyTime) {
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

    public void setQuery(String queryStr) {
        if (queryStr == null || queryStr.length() == 0) {
            this.query = new byte[0];
            return;
        }

        try (ByteArrayOutputStream obj = new ByteArrayOutputStream(); GZIPOutputStream gzip = new GZIPOutputStream(obj)) {
            gzip.write(queryStr.getBytes(StandardCharsets.UTF_8));
            gzip.close();
            this.query = obj.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
