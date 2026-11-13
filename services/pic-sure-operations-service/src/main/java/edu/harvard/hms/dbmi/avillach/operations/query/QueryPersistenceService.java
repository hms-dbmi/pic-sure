package edu.harvard.hms.dbmi.avillach.operations.query;

import java.nio.ByteBuffer;
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
import java.util.function.Function;
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
import edu.harvard.dbmi.avillach.domain.StoredQueryVariant;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
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
 * <p>It is also the only writer of the {@code query_to_variant} table. The save that inserts a row with an owner files it under its general
 * query, and creates the general query in the same transaction when it is not stored yet. General queries are never changed or deleted.
 *
 * <p>{@link #ensureGeneralQuery} stores a general query with no query row under it, for a named dataset whose stored query its user has not
 * run yet.
 *
 * <p>Every insert of a row stamps {@code created_at} with the time of the insert, with or without an owner. Nothing writes the column
 * afterward.
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
    private static final int OWNED_SAVE_MEMBERS = 5;
    private static final int UUID_BYTES = 16;

    private final QueryRepository repo;
    private final QueryRunRepository runs;
    private final QueryToVariantRepository generalQueries;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Creates the service on the system clock.
     *
     * @param repo the query rows
     * @param runs the runs of the query rows
     * @param generalQueries the general queries the owned query rows are filed under
     * @param transactionManager the transaction manager each save opens its transactions on
     */
    @Autowired
    public QueryPersistenceService(
        QueryRepository repo, QueryRunRepository runs, QueryToVariantRepository generalQueries,
        PlatformTransactionManager transactionManager
    ) {
        this(repo, runs, generalQueries, transactionManager, Clock.systemUTC());
    }

    /**
     * Creates the service on a given clock, so a test can decide what time every save and update reads.
     *
     * @param repo the query rows
     * @param runs the runs of the query rows
     * @param generalQueries the general queries the owned query rows are filed under
     * @param transactionManager the transaction manager each save opens its transactions on
     * @param clock the source of the start, ready, submit, completion and creation times
     */
    QueryPersistenceService(
        QueryRepository repo, QueryRunRepository runs, QueryToVariantRepository generalQueries,
        PlatformTransactionManager transactionManager, Clock clock
    ) {
        this.repo = repo;
        this.runs = runs;
        this.generalQueries = generalQueries;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Stores a query row under the id the request carries, or under a random id when it carries none, with the rules of the two-argument
     * {@code save}.
     *
     * @param req the query body, status, version, metadata, and optionally the id, owner, consents, trigger, general query and variant to
     *        store
     * @return the id the row is stored under
     * @throws PicsureException as the two-argument {@code save} does
     */
    public UUID save(SaveQueryRequest req) {
        return save(req.picsureId(), req);
    }

    /**
     * Stores a query row under the id the caller chose. The first save of an id inserts the row with the request's body, version, result
     * id, owner, consents, status, metadata, general query, sample flag and result type. A later save of the same id updates that row in
     * place: it takes the new consents, status and metadata, restarts {@code startTime}, clears {@code readyTime}, and leaves the stored
     * body, version, result id, owner, general query, sample flag, result type and creation time as they were. The insert stamps the
     * creation time, with or without an owner. Consent values are stored sorted. When the request names a trigger, one {@code query_run}
     * row is written in the same transaction as the row change, and neither is kept when the other fails.
     *
     * <p>The request is either legacy-shaped, with none of {@code picsureId}, {@code userUuid}, {@code consents}, {@code trigger} and
     * {@code queryToVariantUuid}, or fully owned, with all five. An empty consent map counts as present. Any other mix is refused before
     * anything is read or written, so a row without an owner is never filed under a general query and a new row with an owner always is.
     *
     * <p>A result type is either absent or the name of a {@link ResultType} constant, in the constant's own case. Any other value is
     * refused before anything is read or written, whether or not the request has an owner and whether or not the row is already stored,
     * because no later save rewrites the result type of a row.
     *
     * <p>The save that inserts an owned row also inserts its general query when no row has that id yet, in the transaction of the row
     * insert, so neither is kept when the other fails. A general query that is stored under a different owner than the request names
     * refuses the save, and nothing is stored. A later save of the row neither reads nor writes the general query.
     *
     * <p>A stored row is only ever saved again by its owner. When the owner of the stored row and the owner in the request differ, or one
     * of the two is absent, the save is refused, the row keeps every value it had and no run is written.
     *
     * <p>The save runs in up to three transactions of its own and is not itself transactional. The first locks the row and, when it is
     * there, updates it. When it is not there, that transaction ends before the second one inserts the row, because on MySQL a locking read
     * that finds nothing holds the gap the row would go into, and two first saves that each held it and then inserted would deadlock. Two
     * first saves of one id can therefore both insert. The one that loses fails on the primary key, its transaction rolls back, and a third
     * transaction locks the row the other one inserted and updates it under the rules above. Only a duplicate key on the row insert leads
     * to that third transaction, and any other failure of the insert is thrown as it is. When the third transaction finds no row under the
     * id, the duplicate was on the variant key of the general query, and the duplicate-key failure is thrown as the database reported it. A
     * caller that already holds a transaction joins it, and the third step then cannot succeed, because the failed insert has marked that
     * transaction for rollback.
     *
     * @param picsureId the row id to store under, or null to have a random one assigned
     * @param req the query body, status, version, metadata, owner, consents, trigger, general query and variant to store
     * @return the id the row is stored under
     * @throws PicsureException 400 when the request is neither legacy-shaped nor fully owned, when the status, trigger or result type is
     *         not a known name, or when the metadata is not base64; 409 when the row or its general query is stored under a different owner
     * @throws DataIntegrityViolationException when the general query already holds a row of the same sample flag and result type under
     *         another id
     */
    public UUID save(UUID picsureId, SaveQueryRequest req) {
        requireLegacyOrFullyOwned(req);
        requireKnownResultType(req.resultType());
        if (picsureId == null) {
            return insertUnderARandomId(req);
        }
        UUID storedOnExistingRow = transactions.execute(status -> storeOnExistingRow(picsureId, req));
        if (storedOnExistingRow != null) {
            return storedOnExistingRow;
        }
        try {
            return transactions.execute(status -> insert(picsureId, req));
        } catch (DuplicateRowException lostFirstInsert) {
            UUID storedOnTheWinnersRow = transactions.execute(status -> storeOnExistingRow(picsureId, req));
            if (storedOnTheWinnersRow == null) {
                throw lostFirstInsert.duplicateKey();
            }
            return storedOnTheWinnersRow;
        }
    }

    /**
     * Makes sure a general query is stored and belongs to the given owner, without storing a query row under it. The row is inserted when
     * no row has the id, and a stored row is left exactly as it is. A named dataset is filed this way under the general query of a stored
     * query its user has not run yet, so the rows a later submit of that query saves are found under the dataset.
     *
     * <p>The method runs in the caller's transaction when the caller has one, and in a transaction of its own otherwise. The row is read
     * with a shared lock after the insert, so a general query another transaction committed after the first plain read of the caller's
     * transaction is found. When the method throws inside a caller's transaction, that transaction can only roll back.
     *
     * @param generalId the id of the general query
     * @param owner the UUID of the user the general query must belong to
     * @return the id of the general query
     * @throws PicsureException 409 when the general query is stored under a different owner
     */
    public UUID ensureGeneralQuery(UUID generalId, UUID owner) {
        return transactions.execute(status -> storedGeneralQuery(generalId, owner, generalQueries::findSharedByUuid));
    }

    /**
     * Returns the owner of a stored general query, with a plain read that takes no lock. General queries are never changed or deleted, so
     * an owner that is found stays the owner. Inside a caller's transaction on MySQL a plain read can miss a general query that another
     * transaction committed after the first plain read of the caller's transaction.
     *
     * @param generalId the id of the general query
     * @return the UUID of the user the general query belongs to, or empty when no general query is found under the id
     */
    public Optional<UUID> ownerOfGeneralQuery(UUID generalId) {
        return generalQueries.findById(generalId).map(QueryToVariant::getUserUuid);
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
        } catch (DuplicateRowException randomIdTaken) {
            throw randomIdTaken.duplicateKey();
        }
    }

    /**
     * Inserts a new row under the given id, filed under the general query the request names. The general query is found or created first,
     * so the caller's transaction holds both writes.
     *
     * @throws PicsureException 409 when the general query is stored under a different owner than the request names
     * @throws DuplicateRowException when the database refuses the row for a duplicate key
     */
    private UUID insert(UUID picsureId, SaveQueryRequest req) {
        Query entity = new Query();
        entity.setUuid(picsureId);
        entity.setQuery(stripResourceCredentials(req.query()));
        entity.setResourceResultId(req.resourceResultId());
        entity.setVersion(req.version());
        entity.setUserUuid(req.userUuid());
        entity.setQueryToVariantUuid(findOrCreateGeneralQuery(req));
        entity.setSample(Boolean.TRUE.equals(req.sample()));
        entity.setResultType(req.resultType());
        entity.setCreatedAt(insertTime());
        return store(entity, req);
    }

    /** Returns the current time in whole milliseconds, which is what the {@code created_at} columns hold. */
    private Instant insertTime() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Makes sure the general query the request names is stored and belongs to the request's owner, as {@link #storedGeneralQuery} does. The
     * row is loaded with a plain read, which is the first read of the transaction a save inserts in.
     *
     * @param req the save request
     * @return the id of the general query, or null when the request names none
     * @throws PicsureException 409 when the general query is stored under a different owner than the request names
     */
    private UUID findOrCreateGeneralQuery(SaveQueryRequest req) {
        UUID generalId = req.queryToVariantUuid();
        if (generalId == null) {
            return null;
        }
        return storedGeneralQuery(generalId, req.userUuid(), generalQueries::findById);
    }

    /**
     * Makes sure a general query is stored and belongs to the given owner. The row is inserted when no row has the id, with a statement
     * that leaves a stored row alone and raises no duplicate-key failure, and is then loaded to read its owner. Two callers that create one
     * general query at once therefore both go on: the second waits for the first and then finds its row.
     *
     * @param generalId the id of the general query
     * @param owner the UUID of the user the general query must belong to
     * @param loadAfterTheInsert how the row is read once it is stored; it must find a row another transaction committed just before
     * @return the id of the general query
     * @throws PicsureException 409 when the general query is stored under a different owner
     */
    private UUID storedGeneralQuery(UUID generalId, UUID owner, Function<UUID, Optional<QueryToVariant>> loadAfterTheInsert) {
        generalQueries.insertIfAbsent(bytesOf(generalId), bytesOf(owner), insertTime());
        UUID storedOwner = loadAfterTheInsert.apply(generalId).map(QueryToVariant::getUserUuid)
            .orElseThrow(() -> new IllegalStateException("General query " + generalId + " is missing right after it was stored"));
        if (!storedOwner.equals(owner)) {
            throw PicsureExceptions.conflict("General query " + generalId + " belongs to a different owner");
        }
        return generalId;
    }

    /** Returns the 16 bytes of a UUID, most significant byte first, which is how the {@code BINARY(16)} id columns hold one. */
    private static byte[] bytesOf(UUID uuid) {
        return ByteBuffer.allocate(UUID_BYTES).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
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
     * Writes the row to the database now. The {@code query} table has two unique keys: the primary key, and the variant key over the
     * general query, the sample flag and the result type. A duplicate key on the insert of a new row is reported as
     * {@link DuplicateRowException} whichever of the two refused it. It means that another save inserted the same id first, or that the
     * general query already holds a row of the same variant under another id. The caller tells the two apart by looking for a row under the
     * id. Every other failure is thrown as it is.
     */
    private UUID flushRow(Query entity) {
        boolean inserting = entity.isNew();
        try {
            return repo.saveAndFlush(entity).getUuid();
        } catch (DataIntegrityViolationException e) {
            if (inserting && isDuplicateKey(e)) {
                throw new DuplicateRowException(e);
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

    /** Refuses a request that carries some but not all of the id, owner, consents, trigger and general query of an owned save. */
    private static void requireLegacyOrFullyOwned(SaveQueryRequest req) {
        long ownedMembers = Stream.of(req.picsureId(), req.userUuid(), req.consents(), req.trigger(), req.queryToVariantUuid())
            .filter(Objects::nonNull).count();
        if (ownedMembers != 0 && ownedMembers != OWNED_SAVE_MEMBERS) {
            throw PicsureExceptions
                .badRequest("picsureId, userUuid, consents, trigger and queryToVariantUuid must be sent together or not at all");
        }
    }

    /**
     * Refuses a result type that is not the name of a {@link ResultType} constant. No result type at all is allowed.
     *
     * @param resultType the result type the request carries, or null
     * @throws PicsureException 400 when the value is not null and no constant has that exact name
     */
    private static void requireKnownResultType(String resultType) {
        if (resultType == null) {
            return;
        }
        try {
            ResultType.valueOf(resultType);
        } catch (IllegalArgumentException e) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "invalid_result_type", "Unknown result type: " + resultType);
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
     * Lists the variants one user stored under one general query, oldest first, as {@link QueryRepository#findVariantsOldestFirst} orders
     * them. No query body is read.
     *
     * @param generalQuery the id of the general query
     * @param owner the UUID of the user whose rows are listed
     * @return the variants, or an empty list when the user has none under the id, which is also the answer for a general query of another
     *         user and for one that does not exist
     */
    @Transactional(readOnly = true)
    public List<StoredQueryVariant> variantsOf(UUID generalQuery, UUID owner) {
        return repo.findVariantsOldestFirst(generalQuery, owner).stream()
            .map(row -> new StoredQueryVariant(row.uuid(), row.sample(), row.resultType(), toEpochMillis(row.createdAt()))).toList();
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

    /** Thrown out of the insert transaction when the database refused the new row for a duplicate key. */
    private static final class DuplicateRowException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final DataIntegrityViolationException duplicateKey;

        DuplicateRowException(DataIntegrityViolationException duplicateKey) {
            super(duplicateKey);
            this.duplicateKey = duplicateKey;
        }

        /** Returns the duplicate-key failure the database reported. */
        DataIntegrityViolationException duplicateKey() {
            return duplicateKey;
        }
    }
}
