package edu.harvard.hms.dbmi.avillach.operations.query;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;

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
 * the latest run. Runs are never deleted. This service reads only the latest run of a row; the repository can also list every run of a row,
 * which the tests use.
 *
 * <p>A save onto a stored row and an update both lock the query row before they read anything else, so two writers of one row run one after
 * the other and the second sees the run the first appended.
 */
@Service
public class QueryPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(QueryPersistenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    private static final int OWNED_SAVE_MEMBERS = 4;

    private final QueryRepository repo;
    private final QueryRunRepository runs;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Creates the service on the system clock.
     *
     * @param repo the query rows
     * @param runs the runs of the query rows
     * @param transactionManager the transaction manager each save opens its transactions on
     */
    @Autowired
    public QueryPersistenceService(QueryRepository repo, QueryRunRepository runs, PlatformTransactionManager transactionManager) {
        this(repo, runs, transactionManager, Clock.systemUTC());
    }

    /**
     * Creates the service on a given clock, so a test can decide what time every save and update reads.
     *
     * @param repo the query rows
     * @param runs the runs of the query rows
     * @param transactionManager the transaction manager each save opens its transactions on
     * @param clock the source of the start, ready, submit and completion times
     */
    QueryPersistenceService(QueryRepository repo, QueryRunRepository runs, PlatformTransactionManager transactionManager, Clock clock) {
        this.repo = repo;
        this.runs = runs;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Stores a query row under the id the request carries, or under a random id when it carries none, with the rules of the two-argument
     * {@code save}.
     *
     * @param req the query body, status, version, metadata, and optionally the id, owner, consents and trigger to store
     * @return the id the row is stored under
     * @throws PicsureException as the two-argument {@code save} does
     */
    public UUID save(SaveQueryRequest req) {
        return save(req.picsureId(), req);
    }

    /**
     * Stores a query row under the id the caller chose. The first save of an id inserts the row with the request's body, version, result
     * id, owner, consents, status and metadata. A later save of the same id updates that row in place: it takes the new consents, status
     * and metadata, restarts {@code startTime}, clears {@code readyTime}, and leaves the stored body, version, result id and owner as they
     * were. Consent values are stored sorted. When the request names a trigger, one {@code query_run} row is written in the same
     * transaction as the row change, and neither is kept when the other fails.
     *
     * <p>The request is either legacy-shaped, with none of {@code picsureId}, {@code userUuid}, {@code consents} and {@code trigger}, or
     * fully owned, with all four. An empty consent map counts as present. Any other mix is refused before anything is read or written.
     *
     * <p>A stored row is only ever saved again by its owner. When the owner of the stored row and the owner in the request differ, or one
     * of the two is absent, the save is refused, the row keeps every value it had and no run is written.
     *
     * <p>The save runs in up to three transactions of its own and is not itself transactional. The first locks the row and, when it is
     * there, updates it. When it is not there, that transaction ends before the second one inserts the row, because on MySQL a locking read
     * that finds nothing holds the gap the row would go into, and two first saves that each held it and then inserted would deadlock. Two
     * first saves of one id can therefore both insert. The one that loses fails on the primary key, its transaction rolls back, and a third
     * transaction locks the row the other one inserted and updates it under the rules above. Only a duplicate row id leads to that third
     * transaction, and any other failure of the insert is thrown as it is. A caller that already holds a transaction joins it, and the
     * third step then cannot succeed, because the failed insert has marked that transaction for rollback.
     *
     * @param picsureId the row id to store under, or null to have a random one assigned
     * @param req the query body, status, version, metadata, owner, consents and trigger to store
     * @return the id the row is stored under
     * @throws PicsureException 400 when the request is neither legacy-shaped nor fully owned, when the status or trigger is not a known
     *         name, or when the metadata is not base64; 409 when the row is stored under a different owner
     */
    public UUID save(UUID picsureId, SaveQueryRequest req) {
        requireLegacyOrFullyOwned(req);
        if (picsureId == null) {
            return insertUnderARandomId(req);
        }
        UUID storedOnExistingRow = transactions.execute(status -> storeOnExistingRow(picsureId, req));
        if (storedOnExistingRow != null) {
            return storedOnExistingRow;
        }
        try {
            return transactions.execute(status -> insert(picsureId, req));
        } catch (RowIdTakenException lostFirstInsert) {
            UUID storedOnTheWinnersRow = transactions.execute(status -> storeOnExistingRow(picsureId, req));
            if (storedOnTheWinnersRow == null) {
                throw lostFirstInsert.duplicateKey();
            }
            return storedOnTheWinnersRow;
        }
    }

    /**
     * Locks the row with the given id and stores the request onto it, or returns null without writing when no row has the id.
     *
     * @throws PicsureException 409 when the row is stored under a different owner than the request names
     */
    private UUID storeOnExistingRow(UUID picsureId, SaveQueryRequest req) {
        Optional<Query> stored = repo.findLockedByUuid(picsureId);
        if (stored.isEmpty()) {
            return null;
        }
        if (!Objects.equals(stored.get().getUserUuid(), req.userUuid())) {
            throw PicsureExceptions.conflict("Query " + picsureId + " belongs to a different owner");
        }
        return store(stored.get(), req);
    }

    private UUID insertUnderARandomId(SaveQueryRequest req) {
        try {
            return transactions.execute(status -> insert(UUID.randomUUID(), req));
        } catch (RowIdTakenException randomIdTaken) {
            throw randomIdTaken.duplicateKey();
        }
    }

    private UUID insert(UUID picsureId, SaveQueryRequest req) {
        Query entity = new Query();
        entity.setUuid(picsureId);
        entity.setQuery(stripResourceCredentials(req.query()));
        entity.setResourceResultId(req.resourceResultId());
        entity.setVersion(req.version());
        entity.setUserUuid(req.userUuid());
        return store(entity, req);
    }

    private UUID store(Query entity, SaveQueryRequest req) {
        RunTrigger trigger = parseTrigger(req.trigger());
        Map<String, List<String>> consents = sortedConsents(req.consents());
        Instant now = clock.instant();
        entity.setReadyTime(null);
        entity.setConsents(consents);
        entity.setStatus(parseStatus(req.status()));
        entity.setMetadata(decodeMetadata(req.metadata()));
        entity.setStartTime(now);
        UUID picsureId = flushRow(entity);
        if (trigger != null) {
            runs.saveAndFlush(new QueryRun(UUID.randomUUID(), picsureId, consents, trigger, submitTimeAfterLatestRun(picsureId, now)));
        }
        return picsureId;
    }

    /**
     * Writes the row to the database now. The primary key is the only unique key of the {@code query} table, so a duplicate key on the
     * insert of a new row means another save inserted the same id first, and it is reported as {@link RowIdTakenException}. Every other
     * failure is thrown as it is.
     */
    private UUID flushRow(Query entity) {
        boolean inserting = entity.isNew();
        try {
            return repo.saveAndFlush(entity).getUuid();
        } catch (DataIntegrityViolationException e) {
            if (inserting && isDuplicateKey(e)) {
                throw new RowIdTakenException(e);
            }
            throw e;
        }
    }

    /** Returns true when the database refused a write for a duplicate key: SQL state 23505, or MySQL error 1062. */
    private static boolean isDuplicateKey(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState()) || sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY;
            }
        }
        return false;
    }

    /** Refuses a request that carries some but not all of the id, owner, consents and trigger of an owned save. */
    private static void requireLegacyOrFullyOwned(SaveQueryRequest req) {
        long ownedMembers = Stream.of(req.picsureId(), req.userUuid(), req.consents(), req.trigger()).filter(Objects::nonNull).count();
        if (ownedMembers != 0 && ownedMembers != OWNED_SAVE_MEMBERS) {
            throw PicsureExceptions.badRequest("picsureId, userUuid, consents and trigger must be sent together or not at all");
        }
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
     * Applies the non-null fields of the request to a stored row. The row is locked first, so the update sees every run a save committed
     * before it and no save can append a run until the update ends.
     *
     * <p>A request that names a run must name the row's latest run. When the row has a newer run, or no run at all, the update is refused
     * and nothing is changed. That happens when a poller read the row, the query was run again, and the poller then reports on the run it
     * read. A request that names no run is applied whatever the latest run is.
     *
     * <p>The first status of {@code AVAILABLE} stamps {@code readyTime}, and later ones leave it. A status of {@code AVAILABLE} or
     * {@code ERROR} also completes the latest run, once: a run that already has a completion time keeps it and its status.
     *
     * <p>The body and version of a row that has an owner are never rewritten, because the id of an owned row is derived from its body. A
     * request that carries either for an owned row is refused and nothing is changed. A row without an owner takes both, which is how a row
     * stored before v3 is upgraded.
     *
     * @param picsureId the id of the row to update
     * @param req the fields to change
     * @throws PicsureException 404 when no row has the id; 400 when the status is not a known name, the metadata is not base64, or the
     *         request carries a body or version for a row that has an owner; 409 when the request names a run that is not the row's latest
     */
    @Transactional
    public void update(UUID picsureId, UpdateQueryRequest req) {
        Query entity = repo.findLockedByUuid(picsureId).orElseThrow(() -> notFound(picsureId));
        PicSureStatus newStatus = parseStatus(req.status());
        byte[] newMetadata = decodeMetadata(req.metadata());
        if (entity.getUserUuid() != null && (req.query() != null || req.version() != null)) {
            throw PicsureExceptions.badRequest("The query and version of an owned query cannot be changed");
        }
        Optional<QueryRun> latestRun = runs.findFirstByQueryUuidOrderBySubmittedAtDesc(picsureId);
        if (req.runId() != null && !latestRun.map(QueryRun::getRunId).filter(req.runId()::equals).isPresent()) {
            throw PicsureExceptions.conflict("The update names a run that is not the latest run of query " + picsureId);
        }
        if (newStatus != null) {
            Instant now = clock.instant();
            if (newStatus == PicSureStatus.AVAILABLE && entity.getReadyTime() == null) {
                entity.setReadyTime(now);
            }
            entity.setStatus(newStatus);
            if (newStatus == PicSureStatus.AVAILABLE || newStatus == PicSureStatus.ERROR) {
                latestRun.filter(run -> run.getCompletedAt() == null).ifPresent(run -> run.complete(newStatus, now));
            }
        }
        if (req.resourceResultId() != null) {
            entity.setResourceResultId(req.resourceResultId());
        }
        if (newMetadata != null) {
            entity.setMetadata(newMetadata);
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

    /** Thrown out of the insert transaction when another save has already inserted a row under the same id. */
    private static final class RowIdTakenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final DataIntegrityViolationException duplicateKey;

        RowIdTakenException(DataIntegrityViolationException duplicateKey) {
            super(duplicateKey);
            this.duplicateKey = duplicateKey;
        }

        /** Returns the duplicate-key failure the database reported. */
        DataIntegrityViolationException duplicateKey() {
            return duplicateKey;
        }
    }
}
