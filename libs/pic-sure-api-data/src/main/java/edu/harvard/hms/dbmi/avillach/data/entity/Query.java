package edu.harvard.hms.dbmi.avillach.data.entity;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * Ported from the legacy {@code edu.harvard.dbmi.avillach.data.entity.Query} (javax/CDI). The {@code resourceId} FK /
 * {@code @ManyToOne Resource resource} association is intentionally dropped: the {@code resource} registry table/entity is being removed in
 * this migration, the {@code query.resourceId} column is nullable (see legacy V1__CREATE_PICSURE_INITIAL.sql), and the Phase-4 services do
 * not read or write it (new rows simply leave it NULL until it is dropped in a later phase).
 */
@Entity(name = "query")
public class Query extends BaseEntity {

    private Date startTime;

    private Date readyTime;

    // Resource is responsible for mapping internal status to picsurestatus.
    // No @Enumerated annotation in the legacy entity -> default JPA enum mapping is ORDINAL;
    // made explicit here to preserve that mapping unambiguously.
    @Enumerated(EnumType.ORDINAL)
    private PicSureStatus status;

    private String resourceResultId;

    // Original query request, gzip-compressed.
    @Lob
    @Column(columnDefinition = "BLOB")
    private byte[] query;

    @Column(length = 8192)
    private byte[] metadata;

    private String version;

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
}
