package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * Persistence model for the {@code query_run} table: one row for each time a query was submitted to HPDS. A run is written once with its
 * query, consents, trigger and submit time, none of which change afterward. Only {@code completedAt} and {@code status} are filled in
 * later, when the run reaches a final status. Rows are never deleted.
 *
 * <p>The id is assigned before the first save, so the entity implements {@link Persistable} and tracks its own new state the way
 * {@link Query} does.
 */
@Entity(name = "query_run")
public class QueryRun implements Persistable<UUID> {

    @Id
    @Column(name = "run_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID runId;

    @Transient
    private boolean unsaved = true;

    @Column(name = "query_uuid", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID queryUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consents", updatable = false)
    private Map<String, List<String>> consents;

    @Enumerated(EnumType.STRING)
    @Column(name = "`trigger`", length = 32, nullable = false, updatable = false)
    private RunTrigger trigger;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Convert(converter = PicSureStatusConverter.class)
    @Column(name = "status")
    private PicSureStatus status;

    /** Creates an empty run for the persistence provider. */
    protected QueryRun() {}

    /**
     * Creates a run that has been submitted and has not finished.
     *
     * @param runId the id of the run
     * @param queryUuid the id of the query row the run belongs to
     * @param consents the consents the query was scoped with for this run, or null when none were sent
     * @param trigger why the query was submitted
     * @param submittedAt when the query was submitted
     */
    public QueryRun(UUID runId, UUID queryUuid, Map<String, List<String>> consents, RunTrigger trigger, Instant submittedAt) {
        this.runId = runId;
        this.queryUuid = queryUuid;
        this.consents = consents;
        this.trigger = trigger;
        this.submittedAt = submittedAt;
    }

    public UUID getRunId() {
        return runId;
    }

    /** Returns the run id, the same value as {@link #getRunId()}. */
    @Override
    public UUID getId() {
        return runId;
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

    public UUID getQueryUuid() {
        return queryUuid;
    }

    public Map<String, List<String>> getConsents() {
        return consents;
    }

    public RunTrigger getTrigger() {
        return trigger;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public PicSureStatus getStatus() {
        return status;
    }

    /**
     * Records the final status of the run and when it was first seen.
     *
     * @param status the final status
     * @param completedAt when the final status was first seen
     */
    public void complete(PicSureStatus status, Instant completedAt) {
        this.status = status;
        this.completedAt = completedAt;
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof QueryRun other)) {
            return false;
        }
        return this.runId != null && this.runId.equals(other.runId);
    }
}
