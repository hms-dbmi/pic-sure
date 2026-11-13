package edu.harvard.hms.dbmi.avillach.query.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.StoredQueryVariant;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.StoredQueryConsentStripper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.QueryIdentity;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Implements the create, sync, status, result, signed-url, and metadata query lifecycle without a local database. Persistence goes through
 * {@link OperationsClient} over HTTP; operations-service is the sole query store.
 *
 * <p>A submit on {@code auth} by a caller the gateway identified by UUID is stored as a row that caller owns. This service derives the row
 * id with {@link QueryIdentity} before anything is sent, HPDS stores the result under the same id, and the row keeps the consents the query
 * ran under. Such a submit holds the {@link QueryRowLocks} lock of its row from the consent lookup to the save, so two submits of one query
 * by one caller run one after the other. Every other submit, and every synchronous query, is stored under a random id operations-service
 * generates, with no owner, and takes no lock.
 *
 * <p>A status, result or signed-url read of a row that has an owner answers only that owner. The read takes the lock of the row, fetches
 * the caller's consents inside it and compares them with the ones the row records. When they differ, or HPDS no longer holds the job, the
 * read runs the stored query again under the consents it just fetched and saves the row with them. A read of a row without an owner takes
 * no lock, never runs a v3 query again and keeps the check against the authorization filters saved in the row's body.
 *
 * <p>A metadata read is not one of those reads. {@link #queryMetadata} answers every caller for every row, with or without an owner, checks
 * no consent and returns the stored query without any consent value.
 *
 * <p>What is guaranteed for an owned row. A result or a signed URL is fetched only after a check, made inside the lock of the row on that
 * same call, found all four of these: the consents PSAMA returned for the caller on that call equal the ones the row records, the row is
 * not marked in {@link UnsettledQueryRows}, the row is not stored as failed, and HPDS reports a finished job under the row id. The consents
 * a row records are those of the last submit HPDS answered and whose save succeeded. Every submit of a row that is already stored marks the
 * row in {@link UnsettledQueryRows} before the query is sent and removes the mark only after the row is saved. A save that still fails
 * after its retries, a submit whose answer is lost, and any other failure in between leave the row marked, and the next read or submit of
 * that row runs the query again under the consents it just fetched instead of trusting the row. A first submit, which finds no row under
 * its id, marks nothing. Until its save succeeds there is no row to trust: a read of the id answers 404, and the next submit finds no row
 * either and sends the query again under the consents it just fetched. A row stored as failed is answered from the row alone, and nothing
 * HPDS holds under its id is served, because HPDS leaves the job of an earlier run in place when it rejects a query.
 *
 * <p>What is not guaranteed. The locks and the marks live in the memory of one process. When this service stops between the moment HPDS
 * takes a query and the save of the row, or after a failed save and before the next request for that row, the row records consents other
 * than those of the job HPDS holds and nothing remembers it. A caller whose consents equal the recorded ones is then served that job until
 * the consents change, the row is run again, or HPDS restarts. The same is true when more than one instance of this service runs in an
 * environment. The fetch of a result runs after the lock is released and returns what HPDS holds under the row id at that moment. That is
 * the job the check saw, unless another request of the owner ran the query again in between and that run has already finished.
 *
 * <p>Every query runs on HPDS v3 and every new row is stored as version {@code "3"}. {@link #queryStatus}, {@link #queryResult}, and
 * {@link #queryResultSignedUrl} dispatch to the backend selected by the ingress {@code {backend}} segment. A row stored before v3 (any
 * version for which {@link #isV3(StoredQuery)} is false) is first upgraded in place: its body is translated to v3, scoped by the caller's
 * current consents, re-run on HPDS v3, and written back under the same {@code picsureId} as version {@code "3"}. The v1 result it pointed
 * at no longer exists, so a result or signed-url call that triggers the upgrade is answered 409 {@code result_not_ready} and the client
 * polls status as for a new query. {@link #queryMetadata} never upgrades; it only translates the stored body for display.
 */
@Service
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Lenient mapper used ONLY to deserialize a stored v1 {@code query} node in {@link #tryTranslate}: unknown fields on a stored row that
     * predate the current v1 {@code Query} model must not abort translation, so this mapper (unlike {@link #MAPPER}) does not fail on
     * unknown properties. Never used for anything else in this class.
     */
    private static final ObjectMapper V1_QUERY_MAPPER =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final String CURRENT_VERSION = "3";
    private static final String FIRST_SUBMIT = "FIRST_SUBMIT";
    private static final String CONSENT_CHANGE = "CONSENT_CHANGE";
    private static final String RESUBMIT = "RESUBMIT";
    private static final String LOST_RESULT = "LOST_RESULT";
    private static final String RESULT_NOT_READY = "result_not_ready";
    private static final String RESULT_NOT_READY_MESSAGE =
        "The result is not ready. Poll the status of the query and ask again once it reports AVAILABLE.";
    /** The message HPDS itself answers a result request with when the job failed, so the caller reads one message for that case. */
    private static final String FAILED_RESULT_MESSAGE = "Status : ERROR";
    private static final String NOT_THE_OWNER_MESSAGE = "Only the user who submitted this query can read it";
    private static final String STORED_QUERY_UNREADABLE_MESSAGE = "The stored query could not be run again";
    private static final String STATUS_REFUSED_MESSAGE = "HPDS refused to report the status of the query";
    private static final String RESULT_TYPE_REQUIRED_MESSAGE = "An expected result type is required for a query that has not run before";
    /**
     * How many times the save of an owned row is tried once HPDS has answered its submit. The second try follows a stale pooled connection
     * or a single dropped request, and the third a short interruption of operations-service. A longer outage is not waited for: a row that
     * was stored before the submit stays marked in {@link UnsettledQueryRows}, a first submit leaves no row, and either way the next
     * request for the id runs the query again.
     */
    private static final int SAVE_ATTEMPTS = 3;
    /** How long a save waits before its second and before its third try, in milliseconds. The lock of the row is held meanwhile. */
    private static final long[] SAVE_RETRY_PAUSES_MILLIS = {100, 200};
    /**
     * The stored statuses under which a repeated submit reuses the row: HPDS accepted the job and it is waiting for a worker
     * ({@code QUEUED}), running ({@code PENDING}) or finished with a result ({@code AVAILABLE}). A row stored as {@code ERROR}, or with no
     * status, has no job worth waiting for, so a repeated submit runs it again.
     */
    private static final Set<PicSureStatus> REUSABLE_STATUSES =
        EnumSet.of(PicSureStatus.QUEUED, PicSureStatus.PENDING, PicSureStatus.AVAILABLE);
    /** The canonical text form of a UUID: 36 characters, hexadecimal digits in either case, hyphens after digits 8, 12, 16 and 20. */
    private static final Pattern CANONICAL_UUID =
        Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");
    private static final TypeReference<Map<String, Object>> METADATA_SHAPE = new TypeReference<>() {};
    /**
     * JSON property names the v1 {@code Query} model deserializes, read from the model through Jackson so the set follows the class. A
     * stored pre-v3 {@code query} node that carries none of them is not a v1 query.
     */
    private static final Set<String> V1_QUERY_PROPERTIES =
        deserializableProperties(edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.class);
    /**
     * JSON property names the v3 {@code Query} deserializes and the v1 model does not, read the same way as {@link #V1_QUERY_PROPERTIES}. A
     * stored pre-v3 {@code query} node holding any of them is not a v1 query.
     */
    private static final Set<String> V3_ONLY_QUERY_PROPERTIES =
        deserializableProperties(edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class).stream()
            .filter(name -> !V1_QUERY_PROPERTIES.contains(name)).collect(Collectors.toUnmodifiableSet());

    private final OperationsClient operationsClient;
    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;
    private final ConsentAuthorizationService consentAuthorization;
    private final SampleRules sampleRules;
    private final QueryRowLocks rowLocks;
    private final UnsettledQueryRows unsettledRows;

    public QueryService(
        OperationsClient operationsClient, ResourceWebClient hpds, HpdsBackendSelector selector,
        ConsentAuthorizationService consentAuthorization, SampleRules sampleRules, QueryRowLocks rowLocks, UnsettledQueryRows unsettledRows
    ) {
        this.operationsClient = operationsClient;
        this.hpds = hpds;
        this.selector = selector;
        this.consentAuthorization = consentAuthorization;
        this.sampleRules = sampleRules;
        this.rowLocks = rowLocks;
        this.unsettledRows = unsettledRows;
    }

    public record QuerySyncResponse(byte[] body, String queryMetadata) {
    }

    // --- create / sync ---

    public QueryStatus query(String backend, QueryRequest req) {
        return query(backend, req, null);
    }

    public QueryStatus query(String backend, QueryRequest req, String authorizationHeader) {
        return query(backend, req, authorizationHeader, null);
    }

    /**
     * Submits a query for asynchronous execution and stores it.
     *
     * <p>On {@code auth}, for a caller whose gateway user id is a canonical UUID and a body that carries a query, the row belongs to that
     * caller: <ol> <li>A query that cannot be read as a v3 query is refused.</li> <li>A sample query is refused while sampling is turned
     * off, before anything is looked up.</li> <li>A query that names no result type takes the result type of the caller's original variant
     * of it, as {@link #withOriginalResultType} describes, and is refused when the caller has none.</li> <li>The other {@link SampleRules}
     * run on the query with that result type.</li> <li>The authorization filters the body carried are dropped.</li> <li>The row id is
     * derived from the caller's UUID and the query, and replaces any {@code picsureId} the body carried.</li> <li>The request takes the
     * {@link QueryRowLocks} lock of that id and holds it for every step below. A second submit of the same query by the same caller waits
     * here until the first has saved its row.</li> <li>The caller's consents are fetched once and the query is scoped with them, so HPDS
     * receives the filters built from those consents, and none when consent authorization is off.</li> <li>With no row under that id, the
     * query is sent to HPDS under the id and the row is saved with its owner, the consents and a first-submit run. Nothing is marked in
     * {@link UnsettledQueryRows} for an id that has no row.</li> <li>With a row whose consents equal the caller's current ones and whose
     * status is {@code QUEUED}, {@code PENDING} or {@code AVAILABLE}, nothing is sent or saved and the stored status is returned, unless
     * the row is marked in {@link UnsettledQueryRows}. A marked row does not describe the job HPDS holds, so the query is sent again and
     * the row is saved with a lost-result run.</li> <li>With a row whose consents equal the caller's current ones and whose status is
     * {@code ERROR} or absent, the query is sent again and the row is saved with a resubmit run.</li> <li>With a row whose consents differ,
     * the query is sent again and the row is saved with the new consents and a consent-change run.</li> </ol> A row is saved only for an
     * answer from HPDS that carries a status and names no result id other than the derived one. The stored body carries the derived id and
     * no authorization filters. The row's result id is the row id.
     *
     * <p>Every other submit keeps the path without an owner: the {@code open} backend, a caller with no gateway user or one whose id is not
     * a canonical UUID, and a body with no query. HPDS runs the query under a key it derives, and operations-service stores it under a
     * random id, with the result type {@link #saveWithoutAnOwner} reads from the query and nothing else that an owned row carries.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param req the envelope holding the query; its query is replaced by the scoped one
     * @param authorizationHeader the caller's {@code Authorization} header, needed by consent scoping on {@code auth}
     * @param user the caller as the gateway identified them, or null when there is none
     * @return the status of the query, with {@code picsureResultId} set to the id of its row
     * @throws PicsureException 400 when the request is missing, breaks a sample rule, or carries for a caller identified by UUID a query
     *         that is not a v3 query or one that names no result type and has no variant of that caller to take one from; 403
     *         {@code consent_denied} when the caller's consents do not permit the query; 500 when the row under the derived id is not the
     *         caller's; 503 {@code service_unavailable} when another submit of the same query by the same caller holds the row for longer
     *         than the configured wait; whatever consent lookup, HPDS or operations-service raise
     * @throws HpdsCommunicationException when HPDS answers a submit for a caller identified by UUID without a status, or with a result id
     *         other than the derived one
     */
    public QueryStatus query(String backend, QueryRequest req, String authorizationHeader, GatewayUser user) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        Optional<UUID> owner = ownerOf(backend, user);
        if (owner.isEmpty() || req.getQuery() == null) {
            sampleRules.check(backend, req.getQuery());
            return createWithoutOwner(backend, req, authorizationHeader);
        }
        Query submitted = readableV3Query(backend, req.getQuery());
        sampleRules.checkEnabled(submitted);
        Query typed = submitted.expectedResultType() == null ? withOriginalResultType(owner.get(), submitted) : submitted;
        sampleRules.check(backend, typed);
        return createOwned(backend, req, typed, authorizationHeader, owner.get());
    }

    /**
     * Returns the body of an owned submit as a v3 query. A body that cannot be read as one is refused, by {@link SampleRules} when it asks
     * for a sample and by {@link #requireV3Query} otherwise, which is the order the two checks have always answered in.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param queryBody the query of a submit, never null
     * @throws PicsureException 400 when the body cannot be read as a v3 query
     */
    private Query readableV3Query(String backend, Object queryBody) {
        try {
            return requireV3Query(queryBody);
        } catch (PicsureException notAV3Query) {
            sampleRules.check(backend, queryBody);
            throw notAV3Query;
        }
    }

    /**
     * Gives an owned submit that names no result type the result type of the caller's original variant of the query. The variants are the
     * rows the caller stored under the general query {@link QueryIdentity#generalIdFor} derives, which leaves the result type and the
     * {@code sample} flag out, so the lookup needs neither. The original is the first variant in the order operations-service lists them,
     * oldest first, that stores the name of a {@link ResultType} constant. A variant with no result type, or with a name that is not a
     * constant, is passed over.
     *
     * <p>Only the result type is taken from the original. The {@code sample} flag and every other component stay as the client sent them.
     * This runs before the row id is derived and before the row lock and the consent lookup, because the row id depends on the result type.
     *
     * @param owner the caller's UUID
     * @param submitted the query of the request, which names no result type
     * @return a copy of the query that asks for the result type of the original variant
     * @throws PicsureException 400 {@code bad_request} when the caller has no variant of the query that stores a result type; whatever
     *         {@link OperationsClient#variantsOf} raises when the lookup itself fails
     */
    private Query withOriginalResultType(UUID owner, Query submitted) {
        ResultType original = operationsClient.variantsOf(QueryIdentity.generalIdFor(owner, submitted), owner).stream()
            .map(StoredQueryVariant::resultType).map(QueryService::knownResultType).flatMap(Optional::stream).findFirst()
            .orElseThrow(() -> new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", RESULT_TYPE_REQUIRED_MESSAGE));
        return new Query(
            submitted.select(), submitted.authorizationFilters(), submitted.phenotypicClause(), submitted.genomicFilters(), original,
            submitted.picsureId(), submitted.id(), submitted.sample()
        );
    }

    /** Returns the {@link ResultType} constant with the given name, or empty when the name is null or names no constant. */
    private static Optional<ResultType> knownResultType(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ResultType.valueOf(name));
        } catch (IllegalArgumentException notAResultType) {
            return Optional.empty();
        }
    }

    private QueryStatus createWithoutOwner(String backend, QueryRequest req, String authorizationHeader) {
        consentAuthorization.scopeQuery(backend, req, authorizationHeader);
        HpdsTarget target = selector.select(backend); // URL + service token

        QueryStatus results = hpds.query(target, req); // Call HPDS before persisting the query.
        String metadataBase64 = buildMetadataBase64(results);

        UUID picsureId =
            operationsClient.save(saveWithoutAnOwner(req, results.getResourceResultId(), statusName(results.getStatus()), metadataBase64));
        results.setPicsureResultId(picsureId);

        if (results.getResourceResultId() == null) { // Use the generated PIC-SURE id when HPDS omits its result id.
            String fallbackId = picsureId.toString();
            results.setResourceResultId(fallbackId);
            operationsClient.update(picsureId, new UpdateQueryRequest(null, fallbackId, null, null, null));
        }
        results.setResourceID(req.getResourceUUID()); // echo (no Resource entity)
        return results;
    }

    /**
     * Derives the row id of an owned submit and runs {@link #submitOwned} while holding the lock of that row. The id comes from the owner
     * and the query without authorization filters, so it is known before the caller's consents are looked up, and the lookup happens inside
     * the lock.
     *
     * @param submitted the query of the request as a v3 query, with whatever authorization filters the client sent
     * @param owner the caller's UUID
     * @throws PicsureException 503 {@code service_unavailable} when another request holds the lock of the row for longer than the
     *         configured wait; whatever {@link #submitOwned} throws
     */
    private QueryStatus createOwned(String backend, QueryRequest req, Query submitted, String authorizationHeader, UUID owner) {
        Query unscoped = submitted.setAuthorizationFilters(List.of());
        UUID id = QueryIdentity.idFor(owner, unscoped);
        return rowLocks.withLock(id, () -> submitOwned(backend, req, unscoped, authorizationHeader, owner, id));
    }

    /**
     * Looks up the caller's consents, scopes the query with them, and either answers from the row stored under the id or sends the query to
     * HPDS and saves the row. The caller of this method holds the lock of the row, so the consents, the stored row, the query HPDS receives
     * and the saved row all belong to one request at a time.
     *
     * @param unscoped the query with no authorization filters, which is what the id was derived from
     * @param owner the caller's UUID
     * @param id the id {@link QueryIdentity} derived for the owner and the query
     */
    private QueryStatus submitOwned(String backend, QueryRequest req, Query unscoped, String authorizationHeader, UUID owner, UUID id) {
        Map<String, List<String>> consents = consentAuthorization.currentConsents(backend, authorizationHeader);
        req.setQuery(unscoped);
        consentAuthorization.scopeWithConsents(backend, req, consents);
        HpdsTarget target = selector.select(backend);

        Query scoped = (Query) req.getQuery();
        req.setQuery(scoped.setPicsureId(id));

        Optional<StoredQuery> stored = operationsClient.find(id);
        if (stored.isPresent() && !owner.equals(stored.get().userUuid())) {
            logger.error("Stored query {} is not owned by the caller its id was derived for", id);
            throw OperationsClient.storedUnderAnotherOwner();
        }
        Optional<String> trigger = triggerOfASubmit(stored, consents);
        if (trigger.isEmpty()) {
            return storedStatus(stored.get(), req.getResourceUUID());
        }
        return runAndSave(target, req, owner, id, consents, trigger.get());
    }

    /**
     * Decides whether an owned submit sends its query to HPDS, and names the run when it does. The caller of this method holds the lock of
     * the row.
     *
     * @param stored the row under the derived id, or empty when there is none
     * @param consents the caller's current consents
     * @return the trigger of the run, or empty when the row is reused as it stands: its consents equal the caller's, its status is one of
     *         {@link #REUSABLE_STATUSES} and it is not marked in {@link UnsettledQueryRows}
     */
    private Optional<String> triggerOfASubmit(Optional<StoredQuery> stored, Map<String, List<String>> consents) {
        if (stored.isEmpty()) {
            return Optional.of(FIRST_SUBMIT);
        }
        StoredQuery row = stored.get();
        if (!sameConsents(row.consents(), consents)) {
            return Optional.of(CONSENT_CHANGE);
        }
        if (!holdsAJob(row)) {
            return Optional.of(RESUBMIT);
        }
        return unsettledRows.isUnsettled(row.picsureId()) ? Optional.of(LOST_RESULT) : Optional.empty();
    }

    /**
     * Sends the query of an owned row to HPDS under the row id and saves the row with the consents the query was scoped with and a run
     * named by the trigger. The caller of this method holds the lock of the row and fetched the consents inside it.
     *
     * <p>A row that is already stored is marked in {@link UnsettledQueryRows} from just before the query is sent until the row is saved, as
     * {@link #submitMarkingTheRow} and {@link #saveAfterSubmit} describe. When this method throws, that row stays marked unless HPDS showed
     * that it kept nothing of the submit. A first submit has no row yet and is sent by {@link #submitWithoutARow}, which marks nothing.
     *
     * <p>Every save names the general query the row is a variant of, and which variant it is: the id {@link QueryIdentity#generalIdFor}
     * derives for the owner, the {@code sample} flag and the result type name, or null for a query with no result type. All three are read
     * from the query of {@code req}, the same query that is sent to HPDS and stored as the body, on a first submit and on every later run
     * alike. The row id comes from that query as well: {@link #createOwned} derives it on a submit, and on a run of a stored row the query
     * is read from the body stored under it. Authorization filters and ids are in neither canonical form, so scoping the query changes none
     * of the four values.
     *
     * @param target the HPDS backend to run on
     * @param req the envelope whose query is the v3 query to run, already scoped with {@code consents} and carrying {@code id}
     * @param owner the UUID of the row's owner
     * @param id the row id, which is also the id HPDS stores the result under
     * @param consents the consents the query was scoped with, which the row records
     * @param trigger why the query runs, as a run trigger name; {@link #FIRST_SUBMIT} exactly when no row is stored under {@code id}
     * @return HPDS's answer, with the row id as its PIC-SURE id and its result id
     * @throws HpdsCommunicationException when HPDS does not accept the query; nothing is saved
     * @throws PicsureException when HPDS refuses the query, or when the row cannot be saved
     */
    private QueryStatus runAndSave(
        HpdsTarget target, QueryRequest req, UUID owner, UUID id, Map<String, List<String>> consents, String trigger
    ) {
        Query scoped = (Query) req.getQuery();
        QueryStatus results = FIRST_SUBMIT.equals(trigger) ? submitWithoutARow(target, req, id) : submitMarkingTheRow(target, req, id);
        String metadataBase64 = buildMetadataBase64(results);
        String resultId = id.toString();
        saveAfterSubmit(
            new SaveQueryRequest(
                serializeQuery(req.copy().setQuery(scoped.setPicsureId(id).setAuthorizationFilters(List.of()))), resultId,
                statusName(results.getStatus()), CURRENT_VERSION, metadataBase64, id, owner, consents, trigger,
                QueryIdentity.generalIdFor(owner, scoped), scoped.sample(), resultTypeName(scoped.expectedResultType())
            )
        );
        unsettledRows.markSettled(id);
        results.setPicsureResultId(id);
        results.setResourceResultId(resultId);
        results.setResourceID(req.getResourceUUID());
        return results;
    }

    /** Returns whether the row's stored status is one of {@link #REUSABLE_STATUSES}. */
    private static boolean holdsAJob(StoredQuery stored) {
        return REUSABLE_STATUSES.stream().anyMatch(reusable -> reusable.name().equals(stored.status()));
    }

    /**
     * Sends the query of a first submit to HPDS under the id its row will be stored under. No row is stored under the id yet, and nothing
     * is marked in {@link UnsettledQueryRows}, whatever HPDS answers and whether or not the save that follows succeeds.
     *
     * <p>A mark tells a later request not to trust the consents a stored row records, and it is only ever read for a row that was loaded
     * first. While no row is stored there is nothing to trust: a status, result or signed-url read of the id answers 404, and the next
     * submit finds no row, fetches the caller's consents inside the lock and sends the query again as a first submit. HPDS then returns the
     * job it already holds under the id only when that job is still queued or running and was built from the same query with the same
     * authorization filters, and replaces it otherwise, so the row that submit saves describes the job HPDS holds. A mark for an id without
     * a row would also never be removed unless the same query was submitted again, and a caller could leave one behind for every distinct
     * query HPDS failed to answer.
     *
     * @param target the HPDS backend to run on
     * @param req the envelope whose query carries {@code id}
     * @param id the id derived for the owner and the query, under which no row is stored
     * @return HPDS's answer, which shows a job stored under {@code id}
     * @throws HpdsCommunicationException when HPDS fails or its answer does not show such a job
     * @throws PicsureException when HPDS refuses the query with a 4xx
     */
    private QueryStatus submitWithoutARow(HpdsTarget target, QueryRequest req, UUID id) {
        return acceptedUnder(id, hpds.query(target, req, id));
    }

    /**
     * Marks a stored owned row in {@link UnsettledQueryRows} and then sends its query to HPDS under the row id. The mark is set first
     * because from the moment the query is on its way HPDS may hold a job the row does not record.
     *
     * <p>The mark this call set is taken back only when HPDS showed that it kept nothing of the submit: <ul> <li>HPDS answered 4xx. HPDS
     * raises no 4xx of its own on a submit, so the request was turned away before the job map was read or changed.</li> <li>HPDS answered
     * with neither a status nor a result id, which is its answer while it has no encryption key loaded. It gives that answer before it
     * looks at the query.</li> </ul> A row that was marked before this call stays marked in both cases, because HPDS still holds whatever
     * the earlier submit left there. Every other outcome leaves the row marked: a failure or timeout of the call, an answer with no body,
     * an answer under another id, and an accepted answer, whose mark {@link #runAndSave} removes after the save.
     *
     * @param target the HPDS backend to run on
     * @param req the envelope whose query carries {@code id}
     * @param id the id of a row that is already stored
     * @return HPDS's answer, which shows a job stored under {@code id}
     * @throws HpdsCommunicationException when HPDS fails or its answer does not show such a job
     * @throws PicsureException when HPDS refuses the query with a 4xx
     */
    private QueryStatus submitMarkingTheRow(HpdsTarget target, QueryRequest req, UUID id) {
        boolean markedByThisSubmit = unsettledRows.markUnsettled(id);
        QueryStatus answer;
        try {
            answer = hpds.query(target, req, id);
        } catch (PicsureException refusedBeforeHpdsReadTheQuery) {
            takeBack(markedByThisSubmit, id);
            throw refusedBeforeHpdsReadTheQuery;
        }
        if (answer != null && answer.getStatus() == null && answer.getResourceResultId() == null) {
            takeBack(markedByThisSubmit, id);
        }
        return acceptedUnder(id, answer);
    }

    /** Removes the mark of a row when the submit that just ended had set it. A mark an earlier submit left stays. */
    private void takeBack(boolean markedByThisSubmit, UUID id) {
        if (markedByThisSubmit) {
            unsettledRows.markSettled(id);
        }
    }

    /**
     * Saves an owned row after HPDS answered its submit, trying up to {@link #SAVE_ATTEMPTS} times with the pauses of
     * {@link #SAVE_RETRY_PAUSES_MILLIS} in between. Only a failure that can pass on its own is tried again: a 502 or 504 from
     * {@link OperationsClient}. A save operations-service refuses because the row has another owner is not. A save that failed may still
     * have been stored, so a later try can add a second run for the one submit.
     *
     * @param row the row to save
     * @throws PicsureException the failure of the last try. A row that was stored before the submit is then still marked in
     *         {@link UnsettledQueryRows}.
     */
    private void saveAfterSubmit(SaveQueryRequest row) {
        for (int attempt = 1;; attempt++) {
            try {
                operationsClient.save(row);
                return;
            } catch (PicsureException failed) {
                if (attempt == SAVE_ATTEMPTS || !canPassOnItsOwn(failed) || !pausedBeforeAttempt(attempt + 1)) {
                    logger.error(
                        "Stored query {} was not saved after HPDS took its query. The next request for it runs the query again.",
                        row.picsureId()
                    );
                    throw failed;
                }
                logger.warn("Saving stored query {} failed on try {} of {}", row.picsureId(), attempt, SAVE_ATTEMPTS);
            }
        }
    }

    private static boolean canPassOnItsOwn(PicsureException saveFailure) {
        return saveFailure.getStatus() == HttpStatus.BAD_GATEWAY || saveFailure.getStatus() == HttpStatus.GATEWAY_TIMEOUT;
    }

    /**
     * Waits the pause that precedes a try of a save.
     *
     * @param attempt the number of the try about to start, 2 or 3
     * @return true after the pause, false when the thread was interrupted, which leaves it interrupted
     */
    private static boolean pausedBeforeAttempt(int attempt) {
        try {
            Thread.sleep(SAVE_RETRY_PAUSES_MILLIS[attempt - 2]);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Returns HPDS's answer to a submit when it shows a job stored under the id this service derived.
     *
     * @param id the id the query was submitted under
     * @param answer what HPDS answered, or null when it answered with no body
     * @return the answer
     * @throws HpdsCommunicationException when the answer is missing, carries no status, or names a result id other than {@code id}; the
     *         message repeats nothing HPDS said
     */
    private static QueryStatus acceptedUnder(UUID id, QueryStatus answer) {
        boolean accepted = answer != null && answer.getStatus() != null
            && (answer.getResourceResultId() == null || id.toString().equalsIgnoreCase(answer.getResourceResultId()));
        if (!accepted) {
            throw new HpdsCommunicationException("HPDS did not accept the query");
        }
        return answer;
    }

    /**
     * Returns the caller's UUID when the backend is {@code auth} and the gateway user id is a UUID in its canonical text form, and empty
     * otherwise. Letter case is not part of the identity: an id in upper case names the same owner as its lower case form.
     */
    private static Optional<UUID> ownerOf(String backend, GatewayUser user) {
        if (!HpdsBackendSelector.AUTH.equals(backend) || user == null || user.getUserId() == null) {
            return Optional.empty();
        }
        if (!CANONICAL_UUID.matcher(user.getUserId()).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(user.getUserId()));
    }

    /**
     * Returns the body as a v3 query.
     *
     * @param queryBody the query of a submit, never null
     * @throws PicsureException 400 {@code bad_request} when the body cannot be read as a v3 query
     */
    private static Query requireV3Query(Object queryBody) {
        if (queryBody instanceof Query typed) {
            return typed;
        }
        try {
            return MAPPER.convertValue(queryBody, Query.class);
        } catch (IllegalArgumentException notAQuery) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Incorrectly formatted request");
        }
    }

    /** Returns whether two consent maps name the same concept paths with the same values, whatever order the values are listed in. */
    private static boolean sameConsents(Map<String, List<String>> stored, Map<String, List<String>> current) {
        return stored != null && asSets(stored).equals(asSets(current));
    }

    private static Map<String, Set<String>> asSets(Map<String, List<String>> consents) {
        Map<String, Set<String>> sets = new HashMap<>();
        consents.forEach((conceptPath, values) -> sets.put(conceptPath, values == null ? Set.of() : new HashSet<>(values)));
        return sets;
    }

    /**
     * Builds an answer from a stored row alone: the row's status, id, result id and result metadata.
     *
     * @param stored the row
     * @param resourceId the resource UUID to echo, or null
     */
    private QueryStatus storedStatus(StoredQuery stored, UUID resourceId) {
        QueryStatus status = new QueryStatus();
        status.setStatus(stored.status() == null ? null : PicSureStatus.valueOf(stored.status()));
        status.setPicsureResultId(stored.picsureId());
        status.setResourceResultId(stored.resourceResultId());
        status.setResourceID(resourceId);
        status.setResultMetadata(storedResultMetadata(stored));
        return status;
    }

    private Map<String, Object> storedResultMetadata(StoredQuery stored) {
        if (stored.metadata() == null) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(decodeMetadata(stored.metadata()), METADATA_SHAPE);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            logger.warn("Unable to read stored result metadata for {}", stored.picsureId(), e);
            return new HashMap<>();
        }
    }

    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource) {
        return querySync(backend, req, requestSource, null);
    }

    /**
     * Runs a query on HPDS's synchronous route and records it. HPDS returns its query id in the {@code queryMetadata} header for every
     * result type it computes as a query; INFO_COLUMN_LISTING is a listing rather than a query and carries no id, so nothing is recorded
     * for it. An HPDS rejection propagates from {@link ResourceWebClient} before anything is persisted.
     *
     * <p>A sample query never runs here. {@link SampleRules} refuses it unless it asks for a dataframe export on {@code auth} with sampling
     * turned on, and HPDS answers that one with 400 because its synchronous route does not serve the dataframe exports.
     */
    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource, String authorizationHeader) {
        return querySync(backend, req, requestSource, authorizationHeader, null);
    }

    /**
     * Runs a query on HPDS's synchronous route as {@link #querySync(String, QueryRequest, String, String)} describes. A synchronous query
     * is stored without an owner whoever sends it, so the caller's identity changes nothing here. The row is stored with the result type
     * {@link #saveWithoutAnOwner} reads from the query, and with no status and no metadata.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param req the envelope holding the query
     * @param requestSource the {@code request-source} header to forward to HPDS, or null
     * @param authorizationHeader the caller's {@code Authorization} header, needed by consent scoping on {@code auth}
     * @param user the caller as the gateway identified them, or null when there is none; not read
     * @return the result bytes and the HPDS query id header
     */
    public QuerySyncResponse querySync(
        String backend, QueryRequest req, String requestSource, String authorizationHeader, GatewayUser user
    ) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        sampleRules.check(backend, req.getQuery());
        consentAuthorization.scopeQuery(backend, req, authorizationHeader);
        HpdsTarget target = selector.select(backend);

        ResourceWebClient.QuerySyncResult down = hpds.querySync(target, req, requestSource);
        if (down.queryMetadata() != null) {
            operationsClient.save(saveWithoutAnOwner(req, down.queryMetadata(), null, null));
        }

        return new QuerySyncResponse(down.body(), down.queryMetadata());
    }

    /** Serializes non-empty result metadata as base64-encoded UTF-8 JSON. */
    private String buildMetadataBase64(QueryStatus response) {
        Map<String, Object> meta = response.getResultMetadata();
        if (meta == null) {
            meta = new HashMap<>();
        }
        response.setResultMetadata(meta);
        if (meta.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = MAPPER.writeValueAsString(meta).getBytes(StandardCharsets.UTF_8); // raw UTF-8 bytes (NOT gzip)
            return Base64.getEncoder().encodeToString(raw);
        } catch (JsonProcessingException e) {
            logger.warn("Unable to serialize query metadata", e);
            return null;
        }
    }

    /**
     * null query → null blob; else the serialized QueryRequest. {@code QueryRequest} no longer carries a credential map, so what reaches
     * the persistence store and the /metadata queryJson echo holds nothing secret; operations-service still strips the field off rows
     * written before its removal.
     */
    private String serializeQuery(QueryRequest req) {
        if (req.getQuery() == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(req);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Incorrectly formatted request");
        }
    }

    /**
     * Builds the save of a row that has no owner. The request names no row id, owner, consents, trigger or general query, which is what
     * makes operations-service store it under a random id with no owner and under no general query. It leaves {@code sample} unset, which
     * is stored as false.
     *
     * <p>The one variant member it names is the result type, read from the query of {@code req}, the same query that is stored as the body,
     * so the row does not wait for a later pass over the stored bodies to learn it. A typed v3 query gives the name of its result type. An
     * untyped body is converted to a v3 query first, as an owned submit converts it. The result type is null when the request has no query,
     * when the query names no result type, and when the body cannot be read as a v3 query. Such a body is still stored as it was sent, and
     * the save is never refused for it.
     *
     * @param req the envelope whose query was sent to HPDS
     * @param resourceResultId the id HPDS assigned to the result, or null when it assigned none
     * @param status the status as a {@link PicSureStatus} constant name, or null for none
     * @param metadataBase64 result metadata as base64 text, or null for none
     * @return the request to send to operations-service
     */
    private SaveQueryRequest saveWithoutAnOwner(QueryRequest req, String resourceResultId, String status, String metadataBase64) {
        return new SaveQueryRequest(
            serializeQuery(req), resourceResultId, status, CURRENT_VERSION, metadataBase64, null, null, null, null, null, null,
            resultTypeNameOf(req.getQuery())
        );
    }

    /**
     * Returns the result type name of a query body that may be untyped.
     *
     * @param queryBody the query of a request envelope: a v3 {@link Query}, an untyped body shaped like one, anything else, or null
     * @return the constant name, or null when the body is absent, names no result type or cannot be read as a v3 query
     */
    private static String resultTypeNameOf(Object queryBody) {
        if (queryBody == null) {
            return null;
        }
        if (queryBody instanceof Query typed) {
            return resultTypeName(typed.expectedResultType());
        }
        try {
            return resultTypeName(MAPPER.convertValue(queryBody, Query.class).expectedResultType());
        } catch (IllegalArgumentException notAV3Query) {
            return null;
        }
    }

    private static String statusName(PicSureStatus status) {
        return status == null ? null : status.name();
    }

    /**
     * Returns the name a result type is saved under.
     *
     * @param resultType the result type of a query, or null when the query has none
     * @return the constant name, or null for a query with no result type
     */
    private static String resultTypeName(ResultType resultType) {
        return resultType == null ? null : resultType.name();
    }

    // --- read ops: stored rows older than v3 are upgraded before dispatch ---

    public QueryStatus queryStatus(String backend, UUID picsureId) {
        return queryStatus(backend, picsureId, null);
    }

    public QueryStatus queryStatus(String backend, UUID picsureId, String authorizationHeader) {
        return queryStatus(backend, picsureId, authorizationHeader, null);
    }

    /**
     * Reports the status of a stored query.
     *
     * <p>A row without an owner is read as it was before rows had owners: a row stored before v3 is upgraded first, HPDS is asked for the
     * status of the result, and that status is recorded on the row. Nothing about the caller is checked and no lock is taken.
     *
     * <p>A row with an owner is read only by that owner, on {@code auth}. Any other caller is refused as soon as the row is loaded, before
     * a lock is taken or anything else is looked up. For the owner, the request takes the {@link QueryRowLocks} lock of the row and runs
     * {@link #checkOwnedRow} inside it. When that check ran the query again, the answer of the new submit is returned. When it found the
     * row stored as failed, {@code ERROR} is returned from the row and nothing is asked of HPDS or written. Otherwise the status HPDS
     * reports is recorded for the run the row was loaded with and returned, still inside the lock. A write that operations-service refuses
     * because the row has a newer run by then is dropped, and the status is returned all the same.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param picsureId the id of the stored query
     * @param authorizationHeader the caller's {@code Authorization} header
     * @param user the caller as the gateway identified them, or null when there is none
     * @return the status of the job HPDS holds for the query, with {@code picsureResultId} set to the id of the row
     * @throws PicsureException 404 {@code not_found} when no row has the id; 403 {@code forbidden} when the row has an owner and the caller
     *         is not that owner; 403 {@code consent_denied} when the owner holds no consents, or the consents do not cover a query that has
     *         to run again; 400 {@code sample_disabled} when a sample query has to run again while sampling is turned off; 503
     *         {@code service_unavailable} when another request holds the row for longer than the configured wait; whatever consent lookup,
     *         HPDS or operations-service raise
     * @throws HpdsCommunicationException when HPDS fails, answers for an owned row without a status, refuses the status request of an owned
     *         row with a 4xx other than 404, or does not accept a query that is run again
     */
    public QueryStatus queryStatus(String backend, UUID picsureId, String authorizationHeader, GatewayUser user) {
        StoredQuery loaded = load(picsureId);
        if (loaded.userUuid() != null) {
            UUID owner = requireOwner(backend, loaded, user);
            return rowLocks.withLock(picsureId, () -> ownedStatus(backend, picsureId, authorizationHeader, owner));
        }
        StoredQuery stored = upgradeToV3(backend, loaded, authorizationHeader);
        QueryStatus status = hpds.queryStatus(selector.select(backend), stored.resourceResultId());
        status.setPicsureResultId(picsureId);
        operationsClient.update(picsureId, new UpdateQueryRequest(statusName(status.getStatus()), null, null, null, null));
        status.setResourceID(resourceUuidFromStored(stored));
        return status;
    }

    /**
     * Answers a status request for an owned row. The caller of this method holds the lock of the row.
     *
     * @param id the id of the row
     * @param owner the caller's UUID, already checked against the owner of the row
     */
    private QueryStatus ownedStatus(String backend, UUID id, String authorizationHeader, UUID owner) {
        CheckedJob job = checkOwnedRow(backend, id, authorizationHeader, owner);
        if (job.source() != JobSource.HPDS) {
            return job.status();
        }
        QueryStatus status = job.status();
        status.setPicsureResultId(id);
        UpdateQueryRequest statusOfTheRun =
            new UpdateQueryRequest(statusName(status.getStatus()), null, null, null, null, job.row().runId());
        if (!operationsClient.updateLatestRun(id, statusOfTheRun)) {
            logger.info("Did not record a status on stored query {} because the query has been run again since the row was read", id);
        }
        status.setResourceID(resourceUuidFromStored(job.row()));
        return status;
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId) {
        return queryResult(backend, picsureId, null);
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, String authorizationHeader) {
        return queryResult(backend, picsureId, authorizationHeader, null);
    }

    /**
     * Fetches the result bytes of a stored query.
     *
     * <p>A row without an owner is read as it was before rows had owners: a row stored before v3 is upgraded first, the caller's consents
     * must still cover the authorization filters saved in the row's body, and the result is fetched from HPDS. Nothing is ever run again
     * for such a row.
     *
     * <p>A row with an owner is read only by that owner, on {@code auth}, and {@link #fetchOwnedResult} decides whether there is a result
     * to fetch.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param picsureId the id of the stored query
     * @param authorizationHeader the caller's {@code Authorization} header
     * @param user the caller as the gateway identified them, or null when there is none
     * @return the fully buffered result, with the content type HPDS gave it
     * @throws PicsureException 404 {@code not_found} when no row has the id, or HPDS holds no result for a row without an owner; 403
     *         {@code forbidden} when the row has an owner and the caller is not that owner; 403 {@code consent_denied} when the caller
     *         holds no consents or, for a row without an owner, no longer holds the saved ones; 409 {@code result_not_ready} when the query
     *         is queued or running, or was run again by this request; 400 {@code bad_request} when the query failed, also when it failed in
     *         the run this request started; 400 {@code sample_disabled} when a sample query has to run again while sampling is turned off;
     *         503 {@code service_unavailable} when another request holds the row for longer than the configured wait; whatever consent
     *         lookup, HPDS or operations-service raise
     * @throws HpdsCommunicationException when HPDS fails. A query is never run again because HPDS failed.
     */
    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, String authorizationHeader, GatewayUser user) {
        StoredQuery loaded = load(picsureId);
        if (loaded.userUuid() != null) {
            return fetchOwnedResult(backend, loaded, authorizationHeader, user, target -> hpds.queryResult(target, picsureId.toString()));
        }
        StoredQuery stored = upgradeToV3(backend, loaded, authorizationHeader);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        HpdsTarget target = selector.select(backend);
        return whenFinished(() -> hpds.queryResult(target, stored.resourceResultId()));
    }

    public SignedUrlResponse queryResultSignedUrl(String backend, UUID picsureId) {
        return queryResultSignedUrl(backend, picsureId, null);
    }

    public SignedUrlResponse queryResultSignedUrl(String backend, UUID picsureId, String authorizationHeader) {
        return queryResultSignedUrl(backend, picsureId, authorizationHeader, null);
    }

    /**
     * Asks HPDS for a signed URL to the result of a stored query. The row is checked exactly as {@link #queryResult} checks it, and the
     * same answers stand for a caller who is not the owner, a result that is not ready and a query that failed.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param picsureId the id of the stored query
     * @param authorizationHeader the caller's {@code Authorization} header
     * @param user the caller as the gateway identified them, or null when there is none
     * @return the signed URL HPDS answered with
     * @throws PicsureException as {@link #queryResult} does
     * @throws HpdsCommunicationException when HPDS fails. A query is never run again because HPDS failed.
     */
    public SignedUrlResponse queryResultSignedUrl(String backend, UUID picsureId, String authorizationHeader, GatewayUser user) {
        StoredQuery loaded = load(picsureId);
        if (loaded.userUuid() != null) {
            return fetchOwnedResult(
                backend, loaded, authorizationHeader, user, target -> hpds.queryResultSignedUrl(target, picsureId.toString())
            );
        }
        StoredQuery stored = upgradeToV3(backend, loaded, authorizationHeader);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        HpdsTarget target = selector.select(backend);
        return whenFinished(() -> hpds.queryResultSignedUrl(target, stored.resourceResultId()));
    }

    /** Where a read of an owned row took the status of the row's job from. */
    private enum JobSource {
        /** This read ran the query again, and the status is HPDS's answer to that submit. */
        NEW_RUN,
        /** HPDS reported the status of the job it holds under the row id. */
        HPDS,
        /** The row is stored as failed, and the status was built from the row without asking HPDS. */
        ROW
    }

    /**
     * What a read of an owned row found once the caller's consents and the job of the row were checked.
     *
     * @param row the row as it was loaded inside the lock
     * @param target the HPDS backend the row's job runs on
     * @param status the status of the row's job
     * @param source where the status comes from
     */
    private record CheckedJob(StoredQuery row, HpdsTarget target, QueryStatus status, JobSource source) {
    }

    /**
     * Returns the caller's UUID when the caller owns the row, and refuses the caller otherwise. The caller's identity is the one the
     * gateway sent and nothing else. A caller with no gateway user, one whose id is not a canonical UUID, and any caller on a backend other
     * than {@code auth} own no row. Nothing is looked up, sent, written or logged here.
     *
     * @param row a row that has an owner
     * @param user the caller as the gateway identified them, or null when there is none
     * @throws PicsureException 403 {@code forbidden} with a fixed message when the caller is not the owner of the row
     */
    private static UUID requireOwner(String backend, StoredQuery row, GatewayUser user) {
        Optional<UUID> caller = ownerOf(backend, user);
        if (caller.isEmpty() || !caller.get().equals(row.userUuid())) {
            throw new PicsureException(HttpStatus.FORBIDDEN, "forbidden", NOT_THE_OWNER_MESSAGE);
        }
        return caller.get();
    }

    /**
     * Checks an owned row against the caller's current consents and against HPDS, and runs the stored query again when a check calls for
     * it. The caller of this method holds the lock of the row, so no other request reads, runs or saves the row in between: <ol> <li>The
     * row is loaded again, so this request sees whatever the request that held the lock before it stored.</li> <li>The caller's consents
     * are fetched.</li> <li>When they differ from the consents the row records, in either direction, the query is run again under them as a
     * consent change. HPDS is not asked about the job it holds, because that job ran under other consents.</li> <li>Otherwise, when the row
     * is marked in {@link UnsettledQueryRows}, the query is run again as a lost result. The row does not describe the job HPDS holds, so
     * equal consents prove nothing about that job.</li> <li>Otherwise, when the row is stored as {@code ERROR}, that failure is the answer.
     * HPDS is not asked, nothing is run and nothing is written. A query HPDS rejects is never stored there, so asking would find no job, or
     * the job of an earlier run that HPDS left in place, and that job ran under consents the row does not record. A failed row runs again
     * on a submit or once the consents change.</li> <li>Otherwise HPDS is asked for the status of the job stored under the row id. When
     * HPDS answers 404, the query is run again as a lost result.</li> <li>Otherwise the status HPDS reported is returned and nothing is run
     * or written.</li> </ol> A failure of HPDS is not a lost result and runs nothing.
     *
     * @param id the id of the row
     * @param owner the caller's UUID, already checked against the owner of the row
     * @return the row, the status of its job, and where that status comes from
     * @throws PicsureException 403 {@code consent_denied} when the caller holds no consents; whatever {@link #runAgain} throws
     * @throws HpdsCommunicationException when HPDS fails, answers the status request without a status, or refuses it with a 4xx other than
     *         404
     */
    private CheckedJob checkOwnedRow(String backend, UUID id, String authorizationHeader, UUID owner) {
        StoredQuery row = load(id);
        Map<String, List<String>> consents = consentAuthorization.currentConsents(backend, authorizationHeader);
        HpdsTarget target = selector.select(backend);
        if (!sameConsents(row.consents(), consents)) {
            return new CheckedJob(row, target, runAgain(backend, target, row, owner, consents, CONSENT_CHANGE), JobSource.NEW_RUN);
        }
        if (unsettledRows.isUnsettled(id)) {
            return new CheckedJob(row, target, runAgain(backend, target, row, owner, consents, LOST_RESULT), JobSource.NEW_RUN);
        }
        if (PicSureStatus.ERROR.name().equals(row.status())) {
            return new CheckedJob(row, target, storedStatus(row, resourceUuidFromStored(row)), JobSource.ROW);
        }
        Optional<QueryStatus> job = jobOf(target, id);
        if (job.isEmpty()) {
            return new CheckedJob(row, target, runAgain(backend, target, row, owner, consents, LOST_RESULT), JobSource.NEW_RUN);
        }
        return new CheckedJob(row, target, job.get(), JobSource.HPDS);
    }

    /**
     * Asks HPDS for the job stored under the id of an owned row.
     *
     * @return the status HPDS reports, or empty when HPDS answers 404 because it holds no job under the id
     * @throws HpdsCommunicationException when HPDS fails, answers without a status, or refuses the request with a 4xx other than 404. Only
     *         a 404 says that HPDS holds no job, so no other refusal runs the query again, and none is passed on to the caller as if it
     *         were this service's own answer. The message is fixed and repeats nothing HPDS said.
     */
    private Optional<QueryStatus> jobOf(HpdsTarget target, UUID id) {
        QueryStatus status;
        try {
            status = hpds.queryStatus(target, id.toString());
        } catch (PicsureException refused) {
            if (refused.getStatus() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new HpdsCommunicationException(STATUS_REFUSED_MESSAGE, refused);
        }
        if (status == null || status.getStatus() == null) {
            throw new HpdsCommunicationException("HPDS did not report the status of the query");
        }
        return Optional.of(status);
    }

    /**
     * Runs the stored query of an owned row again: rebuilds the request from the stored body, applies {@link SampleRules}, scopes the query
     * with the consents the caller of this method fetched, sends it to HPDS under the row id and saves the row with those consents and a
     * run named by the trigger. The caller of this method holds the lock of the row and fetched the consents inside it, so the filters HPDS
     * receives and the consents the row records come from one consent lookup.
     *
     * @param row the row as it was loaded inside the lock
     * @param owner the UUID of the row's owner
     * @param consents the caller's current consents
     * @param trigger why the query runs again, as a run trigger name
     * @return HPDS's answer to the submit, with the row id as its PIC-SURE id and its result id
     * @throws PicsureException 400 {@code sample_disabled} when the row is a sample query and sampling is turned off; 403
     *         {@code consent_denied} when the consents do not cover a filter the query needs; 500 when the stored body cannot be read as a
     *         v3 query; whatever HPDS or operations-service raise. Nothing is saved unless HPDS accepted the query.
     * @throws HpdsCommunicationException when HPDS does not accept the query
     */
    private QueryStatus runAgain(
        String backend, HpdsTarget target, StoredQuery row, UUID owner, Map<String, List<String>> consents, String trigger
    ) {
        UUID id = row.picsureId();
        QueryRequest req = storedOwnedRequest(row);
        sampleRules.check(backend, req.getQuery());
        consentAuthorization.scopeWithConsents(backend, req, consents);
        req.setQuery(((Query) req.getQuery()).setPicsureId(id));
        QueryStatus started = runAndSave(target, req, owner, id, consents, trigger);
        logger.info("Ran stored query {} again, trigger {}", id, trigger);
        return started;
    }

    /**
     * Rebuilds the request an owned row was submitted with from its stored body. Its query is a v3 {@link Query} with no authorization
     * filters, which is how the body of an owned row is stored.
     *
     * @throws PicsureException 500 {@code internal_server_error} with a fixed message when the stored body is not a request that holds a v3
     *         query
     */
    private QueryRequest storedOwnedRequest(StoredQuery row) {
        return readOwnedRequest(row.query()).orElseThrow(() -> {
            logger.error("The body of stored query {} cannot be read as a v3 query request", row.picsureId());
            return new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", STORED_QUERY_UNREADABLE_MESSAGE);
        });
    }

    /** Reads a stored body as a request that holds a v3 query and clears the query's authorization filters, or returns empty. */
    private static Optional<QueryRequest> readOwnedRequest(String body) {
        try {
            QueryRequest req = MAPPER.readValue(body, QueryRequest.class);
            Query query = req == null ? null : MAPPER.convertValue(req.getQuery(), Query.class);
            return Optional.ofNullable(query).map(v3 -> req.setQuery(v3.setAuthorizationFilters(List.of())));
        } catch (JsonProcessingException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Fetches the result of an owned row, or its signed URL, when the row has one to give.
     *
     * <p>The caller must be the owner. The decision is then made by {@link #requireFinishedResult} inside the lock of the row. The fetch
     * itself runs after the lock is released, so a download never keeps other requests for the row waiting.
     *
     * <p>Between the decision and the fetch another request for the row can run the query again, and HPDS can lose the result. HPDS then
     * answers the fetch with 409, which is reported as not ready, or with 404. After a 404 the decision is made once more, which runs the
     * query again unless another request already has, and this request is answered as not ready. What is served is always the result HPDS
     * holds under the row id at the moment of the fetch.
     *
     * @param row the row as first loaded, which has an owner
     * @param user the caller as the gateway identified them, or null when there is none
     * @param fetch the HPDS call that returns the result or its signed URL
     * @param <T> what the fetch returns
     * @return what the fetch returned
     * @throws PicsureException 403 {@code forbidden} when the caller is not the owner; 409 {@code result_not_ready} when HPDS no longer
     *         holds a finished job by the time of the fetch; whatever {@link #requireFinishedResult} throws
     * @throws HpdsCommunicationException when HPDS fails. The query is not run again.
     */
    private <T> T fetchOwnedResult(
        String backend, StoredQuery row, String authorizationHeader, GatewayUser user, Function<HpdsTarget, T> fetch
    ) {
        UUID id = row.picsureId();
        UUID owner = requireOwner(backend, row, user);
        HpdsTarget target = requireFinishedResult(backend, id, authorizationHeader, owner);
        try {
            return fetch.apply(target);
        } catch (PicsureException refused) {
            if (refused.getStatus() != HttpStatus.NOT_FOUND) {
                throw notReadyWhenUnfinished(refused);
            }
        }
        requireFinishedResult(backend, id, authorizationHeader, owner);
        throw resultNotReady();
    }

    /**
     * Decides, while holding the lock of an owned row, whether its result may be fetched. It may when the caller's consents equal the ones
     * the row records, the row is neither marked in {@link UnsettledQueryRows} nor stored as failed, and HPDS holds a finished job under
     * the row id. In every other case the request is answered here. A job that failed is answered as failed whether the row records the
     * failure, HPDS reports it, or HPDS answered with it when {@link #checkOwnedRow} ran the query again. Any other run started by
     * {@link #checkOwnedRow} is answered as not ready, even when HPDS reports it as finished at once.
     *
     * @param id the id of the row
     * @param owner the caller's UUID, already checked against the owner of the row
     * @return the HPDS backend to fetch the result from
     * @throws PicsureException 400 {@code bad_request} when the job failed; 409 {@code result_not_ready} when the query was run again by
     *         this call and did not fail at once, or its job is queued or running; 503 {@code service_unavailable} when another request
     *         holds the row for longer than the configured wait; whatever {@link #checkOwnedRow} throws
     */
    private HpdsTarget requireFinishedResult(String backend, UUID id, String authorizationHeader, UUID owner) {
        return rowLocks.withLock(id, () -> {
            CheckedJob job = checkOwnedRow(backend, id, authorizationHeader, owner);
            PicSureStatus status = job.status().getStatus();
            if (status == PicSureStatus.ERROR) {
                throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", FAILED_RESULT_MESSAGE);
            }
            if (job.source() != JobSource.HPDS || status != PicSureStatus.AVAILABLE) {
                throw resultNotReady();
            }
            return job.target();
        });
    }

    /**
     * Runs a fetch of a result from HPDS and reports the 409 HPDS answers for a job that is still queued or running as
     * {@code result_not_ready}. Every other answer and failure passes through as it is.
     */
    private static <T> T whenFinished(Supplier<T> fetch) {
        try {
            return fetch.get();
        } catch (PicsureException refused) {
            throw notReadyWhenUnfinished(refused);
        }
    }

    private static PicsureException notReadyWhenUnfinished(PicsureException refused) {
        return refused.getStatus() == HttpStatus.CONFLICT ? resultNotReady() : refused;
    }

    /** The one answer for a result or signed URL that cannot be served yet: 409 {@code result_not_ready} with a fixed message. */
    private static PicsureException resultNotReady() {
        return new PicsureException(HttpStatus.CONFLICT, RESULT_NOT_READY, RESULT_NOT_READY_MESSAGE);
    }

    /**
     * Returns {@code stored} unchanged when it is already a v3 row. Otherwise translates its body to v3, scopes the translated query by the
     * caller's current consents exactly as a new submission is scoped, submits it to HPDS v3, and overwrites the same {@code picsureId}
     * with the new result id, status, metadata, query body, and version {@code "3"}. The id the client holds does not change.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param stored the row as loaded from operations-service
     * @param authorizationHeader the caller's {@code Authorization} header, needed by consent scoping on {@code auth}
     * @return the row as it now stands in operations-service
     * @throws PicsureException 422 {@code untranslatable_query} when the stored body cannot be expressed as a v3 query; any exception
     *         consent scoping, HPDS, or operations-service raises for a new submission
     */
    private StoredQuery upgradeToV3(String backend, StoredQuery stored, String authorizationHeader) {
        if (isV3(stored)) {
            return stored;
        }
        QueryRequest req = translatedRequest(stored);
        consentAuthorization.scopeQuery(backend, req, authorizationHeader);
        QueryStatus results = hpds.query(selector.select(backend), req);

        String queryJson = serializeQuery(req);
        String resourceResultId = results.getResourceResultId() != null ? results.getResourceResultId() : stored.picsureId().toString();
        String status = statusName(results.getStatus());
        String metadataBase64 = buildMetadataBase64(results);
        operationsClient
            .update(stored.picsureId(), new UpdateQueryRequest(status, resourceResultId, metadataBase64, queryJson, CURRENT_VERSION));
        logger.info("Upgraded stored query {} (version {}) to version {}", stored.picsureId(), stored.version(), CURRENT_VERSION);
        return new StoredQuery(
            stored.picsureId(), queryJson, resourceResultId, status, CURRENT_VERSION,
            metadataBase64 != null ? metadataBase64 : stored.metadata(), stored.startTime(), stored.readyTime()
        );
    }

    /**
     * Rebuilds the stored pre-v3 {@code QueryRequest} with its nested query translated to v3, or throws 422 when that is not possible.
     * Unlike the display path, the upgrade refuses a nested query that does not look like a v1 {@code Query}: the lenient v1 mapper would
     * read such a node as an empty query, and re-running that would overwrite the saved query with an unfiltered one.
     */
    private QueryRequest translatedRequest(StoredQuery stored) {
        JsonNode root = parseOrNull(stored.query());
        JsonNode translated = root != null && hasV1QueryShape(root) ? tryTranslate(root) : null;
        if (translated != null) {
            try {
                return MAPPER.treeToValue(translated, QueryRequest.class);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                logger.warn("Unable to rebuild translated query {} as a QueryRequest", stored.picsureId(), e);
            }
        }
        throw new PicsureException(
            HttpStatus.UNPROCESSABLE_ENTITY, "untranslatable_query",
            "Query " + stored.picsureId() + " was stored in a format that cannot be converted to the current query format"
        );
    }

    /**
     * Returns whether the stored body's nested {@code query} object carries at least one v1 {@code Query} property and no v3-only property.
     * A body with no object-valued {@code query} returns {@code false}.
     *
     * @param root the parsed stored {@code QueryRequest} body
     */
    static boolean hasV1QueryShape(JsonNode root) {
        JsonNode queryNode = root.get("query");
        if (queryNode == null || !queryNode.isObject()) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        queryNode.fieldNames().forEachRemaining(keys::add);
        return keys.stream().noneMatch(V3_ONLY_QUERY_PROPERTIES::contains) && keys.stream().anyMatch(V1_QUERY_PROPERTIES::contains);
    }

    /** Returns the JSON property names Jackson can deserialize into {@code type}. */
    private static Set<String> deserializableProperties(Class<?> type) {
        return V1_QUERY_MAPPER.getDeserializationConfig().introspect(V1_QUERY_MAPPER.constructType(type)).findProperties().stream()
            .filter(BeanPropertyDefinition::couldDeserialize).map(BeanPropertyDefinition::getName).collect(Collectors.toUnmodifiableSet());
    }

    /** Parses a stored body, returning {@code null} for a null body or one that is not JSON. */
    private static JsonNode parseOrNull(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private StoredQuery load(UUID picsureId) {
        if (picsureId == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query id");
        }
        return operationsClient.get(picsureId); // throws PicsureException(NOT_FOUND) on an unknown id
    }

    /** Returns whether the stored query's major version is 3. A row for which this is false is upgraded before any HPDS read. */
    static boolean isV3(StoredQuery query) {
        String v = query.version();
        return v != null && v.split("\\.")[0].equals(CURRENT_VERSION);
    }

    /** resourceID echo without a Resource entity: parse the resourceUUID out of the stored query JSON. */
    private UUID resourceUuidFromStored(StoredQuery query) {
        try {
            String json = query.query();
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = MAPPER.readTree(json).get("resourceUUID");
            return (node == null || node.isNull()) ? null : UUID.fromString(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    // --- metadata (DB-only, no HPDS) ---

    /**
     * Returns the stored query and the stored result metadata of a query row, read from operations-service alone. HPDS is not called.
     *
     * <p>The read is the same for every caller and every row. It makes no owner or consent check: it does not look at who the caller is,
     * who owns the row or which consents the caller holds, and it does not call the consent service. The only access decision is the one
     * made before this method is reached, the gateway access every query route needs, so any caller the gateway admits who has the id can
     * read the query, and a query can be shared by passing on its id. The answer never carries a consent value. The stored body goes
     * through {@link StoredQueryConsentStripper}, whatever version the row has and whether or not the body could be translated, and the
     * owner, the consents and the run of the row are not copied into the answer.
     *
     * <p>{@code queryResultMetadata} is the result metadata HPDS answered the submit with, as it was stored. HPDS puts one member in it,
     * the id it gave the query, and no filter.
     *
     * @param id the id of the stored query
     * @return the id, resource id, status and result id of the row, with {@code queryJson} and {@code queryResultMetadata} under
     *         {@code resultMetadata}; both are left out when the stored body is not JSON
     * @throws PicsureException 400 when the id is missing, 404 when no row has the id, and whatever operations-service raises
     */
    public QueryStatus queryMetadata(UUID id) {
        if (id == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query id");
        }
        StoredQuery stored = load(id);

        QueryStatus response = new QueryStatus();
        response.setPicsureResultId(stored.picsureId());
        response.setResourceID(resourceUuidFromStored(stored));
        response.setStatus(stored.status() == null ? null : PicSureStatus.valueOf(stored.status()));
        response.setResourceResultId(stored.resourceResultId());

        Map<String, Object> metadata = new HashMap<>();
        try {
            metadata.put("queryJson", buildQueryJson(stored));
            metadata.put("queryResultMetadata", decodeMetadata(stored.metadata()));
        } catch (JsonProcessingException e) {
            logger.warn("Unable to read stored query/metadata for {}", id, e);
        }
        response.setResultMetadata(metadata);
        return response;
    }

    private static String decodeMetadata(String base64Metadata) {
        if (base64Metadata == null) {
            return null;
        }
        return new String(Base64.getDecoder().decode(base64Metadata), StandardCharsets.UTF_8);
    }

    /**
     * The stored-query body for the {@code /metadata} response, without consent values. For a v3 row this is the stored JSON. For a row
     * stored before v3 it is the same {@code QueryRequest} wrapper with its nested {@code query} translated to the v3 shape, so clients see
     * one shape regardless of when the query was stored, and a translation failure falls back to the untranslated body (never an error).
     * Each of the three goes through {@link StoredQueryConsentStripper} before it is returned. A null body gives null, and a body that is
     * not JSON propagates {@link JsonProcessingException}, which leaves {@code queryJson} out of the answer.
     */
    Object buildQueryJson(StoredQuery stored) throws JsonProcessingException {
        String json = stored.query();
        if (json == null) {
            return null;
        }
        JsonNode body = MAPPER.readValue(json, JsonNode.class);
        JsonNode translated = isV3(stored) ? null : tryTranslate(body);
        JsonNode shown = translated != null ? translated : body;
        StoredQueryConsentStripper.strip(shown);
        return MAPPER.convertValue(shown, Object.class);
    }

    /**
     * Attempts to translate a parsed stored v1 {@code QueryRequest} wrapper: deserialize its {@code query} node as a v1 {@code Query},
     * translate to v3, and re-embed it in {@code root}. Returns {@code null} when the body is not a wrapper object, has no object-valued
     * {@code query} node, or cannot be translated
     * ({@link edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException} or any Jackson error). Never throws.
     * {@link #buildQueryJson} then falls back to the raw body; {@link #upgradeToV3} rejects the row with 422. {@code root} is changed only
     * when the translation succeeds.
     */
    JsonNode tryTranslate(JsonNode root) {
        try {
            if (!(root instanceof ObjectNode wrapper)) {
                return null;
            }
            JsonNode queryNode = wrapper.get("query");
            if (queryNode == null || !queryNode.isObject()) {
                return null;
            }
            edu.harvard.hms.dbmi.avillach.hpds.data.query.Query v1 =
                V1_QUERY_MAPPER.treeToValue(queryNode, edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.class);
            edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query v3 = QueryTranslator.translate(v1);
            wrapper.set("query", MAPPER.valueToTree(v3));
            return wrapper;
        } catch (Exception e) {
            logger.warn("Unable to translate stored v1 query to v3", e);
            return null;
        }
    }
}
