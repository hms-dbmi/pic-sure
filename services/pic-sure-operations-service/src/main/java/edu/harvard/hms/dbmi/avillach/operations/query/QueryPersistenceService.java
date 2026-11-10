package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * The sole read/write path onto the {@code query} table for the internal query API ({@link InternalQueryController}). Persists, loads and
 * updates {@link Query} rows and produces the dispatch payload: the stored query JSON with any legacy {@code resourceCredentials} stripped,
 * answered as {@code {queryJson: "<string>"}}.
 *
 * <p>{@code status} travels on the wire as the {@link PicSureStatus} enum NAME, never its ordinal, so callers never need the enum type; an
 * unrecognized name is a caller error (400), not a 500. {@code metadata} travels as base64-encoded bytes, matching the entity's raw
 * {@code byte[]} column; malformed base64 is likewise a caller error (400), not a 500.
 *
 * <p>It is also the only writer of the {@code query_run} table. A save that names a trigger appends one run, and a final status completes
 * the latest run. Runs are never deleted, and they are read only to find a row's latest run.
 */
@Service
public class QueryPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(QueryPersistenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueryRepository repo;
    private final QueryRunRepository runs;
    private final TransactionTemplate transactions;

    public QueryPersistenceService(QueryRepository repo, QueryRunRepository runs, PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.runs = runs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Stores a query row under the id the request carries, or under a random id when it carries none, with the rules of the two-argument
     * {@code save}.
     *
     * @param req the query body, status, version, metadata, and optionally the id, owner, consents and trigger to store
     * @return the id the row is stored under
     */
    public UUID save(SaveQueryRequest req) {
        return save(req.picsureId(), req);
    }

    /**
     * Stores a query row under the id the caller chose. The first save of an id inserts the row with the request's body, version, result
     * id, owner, consents, status and metadata. A later save of the same id updates that row in place: it takes the new consents, status
     * and metadata, restarts {@code startTime}, clears {@code readyTime}, and leaves the stored body, version, result id and owner as they
     * were. Consent values are stored sorted. When the request names a trigger, one {@code query_run} row is written in the same
     * transaction.
     *
     * <p>Two first saves of one id can both find no row and both insert. The insert that loses fails on the primary key, its transaction
     * rolls back, and the save runs again as an update of the row the other one inserted. This method therefore opens its own transaction
     * for each attempt and is not itself transactional. A caller that already holds a transaction joins it, and the second attempt then
     * cannot succeed, because the failed insert has marked that transaction for rollback.
     *
     * @param picsureId the row id to store under, or null to have a random one assigned
     * @param req the query body, status, version, metadata, owner, consents and trigger to store
     * @return the id the row is stored under
     * @throws PicsureException 400 when the status or trigger is not a known name or the metadata is not base64
     */
    public UUID save(UUID picsureId, SaveQueryRequest req) {
        try {
            return transactions.execute(status -> upsert(picsureId, req));
        } catch (DataIntegrityViolationException lostFirstInsert) {
            if (picsureId == null) {
                throw lostFirstInsert;
            }
            return transactions.execute(status -> {
                Query winner = repo.findById(picsureId).orElseThrow(() -> lostFirstInsert);
                return store(winner, req);
            });
        }
    }

    private UUID upsert(UUID picsureId, SaveQueryRequest req) {
        Query entity = picsureId == null ? null : repo.findById(picsureId).orElse(null);
        if (entity == null) {
            entity = new Query();
            entity.setUuid(picsureId == null ? UUID.randomUUID() : picsureId);
            entity.setQuery(stripResourceCredentials(req.query()));
            entity.setResourceResultId(req.resourceResultId());
            entity.setVersion(req.version());
            entity.setUserUuid(req.userUuid());
        }
        return store(entity, req);
    }

    private UUID store(Query entity, SaveQueryRequest req) {
        RunTrigger trigger = parseTrigger(req.trigger());
        Map<String, List<String>> consents = sortedConsents(req.consents());
        Instant now = Instant.now();
        entity.setReadyTime(null);
        entity.setConsents(consents);
        entity.setStatus(parseStatus(req.status()));
        entity.setMetadata(decodeMetadata(req.metadata()));
        entity.setStartTime(now);
        UUID picsureId = repo.saveAndFlush(entity).getUuid();
        if (trigger != null) {
            runs.saveAndFlush(new QueryRun(UUID.randomUUID(), picsureId, consents, trigger, submitTimeAfterLatestRun(picsureId, now)));
        }
        return picsureId;
    }

    /**
     * Returns the submit time for a new run: the current time in whole milliseconds, moved one millisecond past the latest run of the query
     * when the clock has not advanced beyond it. The latest run is found by submit time, so two runs of one query must never share one.
     */
    private Instant submitTimeAfterLatestRun(UUID picsureId, Instant now) {
        Instant submittedAt = now.truncatedTo(ChronoUnit.MILLIS);
        Optional<Instant> latest = runs.findFirstByQueryUuidOrderBySubmittedAtDesc(picsureId).map(QueryRun::getSubmittedAt);
        if (latest.isPresent() && !submittedAt.isAfter(latest.get())) {
            return latest.get().truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
        }
        return submittedAt;
    }

    @Transactional(readOnly = true)
    public StoredQuery get(UUID picsureId) {
        return toDto(load(picsureId));
    }

    /**
     * Applies the non-null fields of the request to a stored row.
     *
     * <p>The status is written unless the request names a run that is not the row's latest one. That happens when a poller read the row,
     * the query was run again, and the poller then reports the status of the run it read. Its status is dropped, and the other fields of
     * the request are still applied. A status of {@code AVAILABLE} or {@code ERROR} that is written also completes the latest run, once: a
     * run that already has a completion time keeps it and its status.
     *
     * @param picsureId the id of the row to update
     * @param req the fields to change
     * @throws PicsureException 404 when no row has the id, 400 when the status is not a known name or the metadata is not base64
     */
    @Transactional
    public void update(UUID picsureId, UpdateQueryRequest req) {
        Query entity = load(picsureId);
        if (req.status() != null) {
            PicSureStatus newStatus = parseStatus(req.status());
            Optional<QueryRun> latestRun = runs.findFirstByQueryUuidOrderBySubmittedAtDesc(picsureId);
            if (namesLatestRun(req.runId(), latestRun)) {
                Instant now = Instant.now();
                if (newStatus == PicSureStatus.AVAILABLE && entity.getReadyTime() == null) {
                    entity.setReadyTime(now); // first AVAILABLE transition only
                }
                entity.setStatus(newStatus);
                if (newStatus == PicSureStatus.AVAILABLE || newStatus == PicSureStatus.ERROR) {
                    latestRun.filter(run -> run.getCompletedAt() == null).ifPresent(run -> run.complete(newStatus, now));
                }
            }
        }
        if (req.resourceResultId() != null) {
            entity.setResourceResultId(req.resourceResultId());
        }
        if (req.metadata() != null) {
            entity.setMetadata(decodeMetadata(req.metadata()));
        }
        if (req.query() != null) {
            entity.setQuery(stripResourceCredentials(req.query()));
        }
        if (req.version() != null) {
            entity.setVersion(req.version());
        }
        repo.save(entity);
    }

    /**
     * Dispatch payload: the stored query JSON, re-serialized as a STRING with any legacy {@code resourceCredentials} removed. 404 when the
     * row is absent. A blank or absent stored query body yields {@code null}, never a 500. Malformed JSON is logged and also yields
     * {@code null} rather than leaking the raw unparsed body.
     */
    @Transactional(readOnly = true)
    public String dispatchQueryJson(UUID picsureId) {
        Query entity = load(picsureId);
        String json = entity.getQuery();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node instanceof ObjectNode obj) {
                obj.remove("resourceCredentials"); // SECURITY: never return stored credentials
            }
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            LOG.warn("Stored query for {} is not valid JSON", picsureId, e);
            return null;
        }
    }

    private Query load(UUID picsureId) {
        return repo.findById(picsureId).orElseThrow(() -> notFound(picsureId));
    }

    private StoredQuery toDto(Query entity) {
        UUID latestRunId = runs.findFirstByQueryUuidOrderBySubmittedAtDesc(entity.getUuid()).map(QueryRun::getRunId).orElse(null);
        return new StoredQuery(
            entity.getUuid(), stripResourceCredentials(entity.getQuery()), entity.getResourceResultId(),
            entity.getStatus() == null ? null : entity.getStatus().name(), entity.getVersion(), encodeMetadata(entity.getMetadata()),
            toEpochMillis(entity.getStartTime()), toEpochMillis(entity.getReadyTime()), entity.getUserUuid(), entity.getConsents(),
            latestRunId
        );
    }

    private static boolean namesLatestRun(UUID runId, Optional<QueryRun> latestRun) {
        return runId == null || latestRun.map(run -> run.getRunId().equals(runId)).orElse(false);
    }

    /** Copies the consents into a map ordered by key with each value list sorted, or returns null when none were sent. */
    private static Map<String, List<String>> sortedConsents(Map<String, List<String>> consents) {
        if (consents == null) {
            return null;
        }
        Map<String, List<String>> sorted = new TreeMap<>();
        consents.forEach((concept, values) -> sorted.put(concept, values == null ? List.of() : values.stream().sorted().toList()));
        return sorted;
    }

    private static RunTrigger parseTrigger(String trigger) {
        if (trigger == null) {
            return null;
        }
        try {
            return RunTrigger.valueOf(trigger);
        } catch (IllegalArgumentException e) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "invalid_trigger", "Unknown trigger: " + trigger);
        }
    }

    private static Long toEpochMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    /**
     * SECURITY: {@code resourceCredentials} must never be persisted nor returned to any internal reader. {@code QueryRequest} dropped the
     * field, so current writers cannot produce one; this remains for rows stored while they could. Bodies without the field pass through
     * byte-identical; malformed JSON passes through unchanged (it cannot carry a parseable credentials field, and dispatch already nulls
     * it).
     */
    private static String stripResourceCredentials(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node instanceof ObjectNode obj && obj.has("resourceCredentials")) {
                obj.remove("resourceCredentials");
                return MAPPER.writeValueAsString(node);
            }
            return json;
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private static PicSureStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return PicSureStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "invalid_status", "Unknown status: " + status);
        }
    }

    private static byte[] decodeMetadata(String metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(metadata);
        } catch (IllegalArgumentException e) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "invalid_metadata", "metadata is not valid base64");
        }
    }

    private static String encodeMetadata(byte[] metadata) {
        return metadata == null ? null : Base64.getEncoder().encodeToString(metadata);
    }

    private static PicsureException notFound(UUID id) {
        return new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Query " + id + " not found");
    }
}
