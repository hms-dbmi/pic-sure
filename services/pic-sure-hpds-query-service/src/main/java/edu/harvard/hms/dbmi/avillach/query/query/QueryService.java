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
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
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
 * ran under. Every other submit, and every synchronous query, is stored under a random id operations-service generates, with no owner.
 *
 * <p>Every query runs on HPDS v3 and every new row is stored as version {@code "3"}. {@link #queryStatus}, {@link #queryResult}, and
 * {@link #queryResultSignedUrl} dispatch to the backend selected by the ingress {@code {backend}} segment. A row stored before v3 (any
 * version for which {@link #isV3(StoredQuery)} is false) is first upgraded in place: its body is translated to v3, scoped by the caller's
 * current consents, re-run on HPDS v3, and written back under the same {@code picsureId} as version {@code "3"}. The v1 result it pointed
 * at no longer exists, so a result or signed-url call that triggers the upgrade receives HPDS's not-ready response and the client polls
 * status as for a new query. {@link #queryMetadata} never upgrades; it only translates the stored body for display.
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

    public QueryService(
        OperationsClient operationsClient, ResourceWebClient hpds, HpdsBackendSelector selector,
        ConsentAuthorizationService consentAuthorization, SampleRules sampleRules
    ) {
        this.operationsClient = operationsClient;
        this.hpds = hpds;
        this.selector = selector;
        this.consentAuthorization = consentAuthorization;
        this.sampleRules = sampleRules;
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
     * caller: <ol> <li>{@link SampleRules} run first.</li> <li>A query that cannot be read as a v3 query is refused.</li> <li>The caller's
     * consents are fetched once and the query is scoped with them.</li> <li>The row id is derived from the caller's UUID and the query, and
     * replaces any {@code picsureId} the body carried.</li> <li>With no row under that id, the query is sent to HPDS under the id and the
     * row is saved with its owner, the consents and a first-submit run.</li> <li>With a row whose consents equal the caller's current ones
     * and whose status is {@code QUEUED}, {@code PENDING} or {@code AVAILABLE}, nothing is sent or saved and the stored status is
     * returned.</li> <li>With a row whose consents equal the caller's current ones and whose status is {@code ERROR} or absent, the query
     * is sent again and the row is saved with a resubmit run.</li> <li>With a row whose consents differ, the query is sent again and the
     * row is saved with the new consents and a consent-change run.</li> </ol> A row is saved only for an answer from HPDS that carries a
     * status and names no result id other than the derived one. The stored body carries the derived id and no authorization filters. The
     * row's result id is the row id.
     *
     * <p>Every other submit keeps the path without an owner: the {@code open} backend, a caller with no gateway user or one whose id is not
     * a canonical UUID, and a body with no query. HPDS runs the query under a key it derives, and operations-service stores it under a
     * random id.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param req the envelope holding the query; its query is replaced by the scoped one
     * @param authorizationHeader the caller's {@code Authorization} header, needed by consent scoping on {@code auth}
     * @param user the caller as the gateway identified them, or null when there is none
     * @return the status of the query, with {@code picsureResultId} set to the id of its row
     * @throws PicsureException 400 when the request is missing, breaks a sample rule, or carries for a caller identified by UUID a query
     *         that is not a v3 query; 403 {@code consent_denied} when the caller's consents do not permit the query; 500 when the row under
     *         the derived id is not the caller's; whatever consent lookup, HPDS or operations-service raise
     * @throws HpdsCommunicationException when HPDS answers a submit for a caller identified by UUID without a status, or with a result id
     *         other than the derived one
     */
    public QueryStatus query(String backend, QueryRequest req, String authorizationHeader, GatewayUser user) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        sampleRules.check(backend, req.getQuery());
        Optional<UUID> owner = ownerOf(backend, user);
        if (owner.isEmpty() || req.getQuery() == null) {
            return createWithoutOwner(backend, req, authorizationHeader);
        }
        return createOwned(backend, req, requireV3Query(req.getQuery()), authorizationHeader, owner.get());
    }

    private QueryStatus createWithoutOwner(String backend, QueryRequest req, String authorizationHeader) {
        consentAuthorization.scopeQuery(backend, req, authorizationHeader);
        HpdsTarget target = selector.select(backend); // URL + service token

        QueryStatus results = hpds.query(target, req); // Call HPDS before persisting the query.
        String metadataBase64 = buildMetadataBase64(results);

        UUID picsureId = operationsClient.save(
            new SaveQueryRequest(
                serializeQuery(req), results.getResourceResultId(), statusName(results.getStatus()), CURRENT_VERSION, metadataBase64
            )
        );
        results.setPicsureResultId(picsureId);

        if (results.getResourceResultId() == null) { // Use the generated PIC-SURE id when HPDS omits its result id.
            String fallbackId = picsureId.toString();
            results.setResourceResultId(fallbackId);
            operationsClient.update(picsureId, new UpdateQueryRequest(null, fallbackId, null, null, null));
        }
        results.setResourceID(req.getResourceUUID()); // echo (no Resource entity)
        return results;
    }

    private QueryStatus createOwned(String backend, QueryRequest req, Query submitted, String authorizationHeader, UUID owner) {
        Map<String, List<String>> consents = consentAuthorization.currentConsents(backend, authorizationHeader);
        req.setQuery(submitted);
        consentAuthorization.scopeWithConsents(backend, req, consents);
        HpdsTarget target = selector.select(backend);

        Query scoped = (Query) req.getQuery();
        UUID id = QueryIdentity.idFor(owner, scoped);
        req.setQuery(scoped.setPicsureId(id));

        Optional<StoredQuery> stored = operationsClient.find(id);
        if (stored.isPresent() && !owner.equals(stored.get().userUuid())) {
            logger.error("Stored query {} is not owned by the caller its id was derived for", id);
            throw OperationsClient.storedUnderAnotherOwner();
        }
        boolean consentsUnchanged = stored.isPresent() && sameConsents(stored.get().consents(), consents);
        if (consentsUnchanged && holdsAJob(stored.get())) {
            return storedStatus(stored.get(), req);
        }

        QueryStatus results = acceptedUnder(id, hpds.query(target, req, id));
        String metadataBase64 = buildMetadataBase64(results);
        String resultId = id.toString();
        operationsClient.save(
            new SaveQueryRequest(
                serializeQuery(req.copy().setQuery(scoped.setPicsureId(id).setAuthorizationFilters(List.of()))), resultId,
                statusName(results.getStatus()), CURRENT_VERSION, metadataBase64, id, owner, consents,
                runTrigger(stored.isPresent(), consentsUnchanged)
            )
        );
        results.setPicsureResultId(id);
        results.setResourceResultId(resultId);
        results.setResourceID(req.getResourceUUID());
        return results;
    }

    /** Returns whether the row's stored status is one of {@link #REUSABLE_STATUSES}. */
    private static boolean holdsAJob(StoredQuery stored) {
        return REUSABLE_STATUSES.stream().anyMatch(reusable -> reusable.name().equals(stored.status()));
    }

    /** Names the run a save records: the first one of a row, one under changed consents, or a repeat under the same consents. */
    private static String runTrigger(boolean rowExists, boolean consentsUnchanged) {
        if (!rowExists) {
            return FIRST_SUBMIT;
        }
        return consentsUnchanged ? RESUBMIT : CONSENT_CHANGE;
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

    /** Builds the answer to a submit that reuses a stored row: the row's status, id, result id and result metadata. */
    private QueryStatus storedStatus(StoredQuery stored, QueryRequest req) {
        QueryStatus status = new QueryStatus();
        status.setStatus(stored.status() == null ? null : PicSureStatus.valueOf(stored.status()));
        status.setPicsureResultId(stored.picsureId());
        status.setResourceResultId(stored.resourceResultId());
        status.setResourceID(req.getResourceUUID());
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
     * is stored without an owner whoever sends it, so the caller's identity changes nothing here.
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
            operationsClient.save(new SaveQueryRequest(serializeQuery(req), down.queryMetadata(), null, CURRENT_VERSION, null));
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

    private static String statusName(PicSureStatus status) {
        return status == null ? null : status.name();
    }

    // --- read ops: stored rows older than v3 are upgraded before dispatch ---

    public QueryStatus queryStatus(String backend, UUID picsureId) {
        return queryStatus(backend, picsureId, null);
    }

    public QueryStatus queryStatus(String backend, UUID picsureId, String authorizationHeader) {
        return queryStatus(backend, picsureId, authorizationHeader, null);
    }

    /**
     * Reads the status of a stored query from HPDS and records it on the row, upgrading a row stored before v3 first.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param picsureId the id of the stored query
     * @param authorizationHeader the caller's {@code Authorization} header
     * @param user the caller as the gateway identified them, or null when there is none; not read
     */
    public QueryStatus queryStatus(String backend, UUID picsureId, String authorizationHeader, GatewayUser user) {
        StoredQuery stored = upgradeToV3(backend, load(picsureId), authorizationHeader);
        QueryStatus status = hpds.queryStatus(selector.select(backend), stored.resourceResultId());
        status.setPicsureResultId(picsureId);
        operationsClient.update(picsureId, new UpdateQueryRequest(statusName(status.getStatus()), null, null, null, null));
        status.setResourceID(resourceUuidFromStored(stored));
        return status;
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId) {
        return queryResult(backend, picsureId, null);
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, String authorizationHeader) {
        return queryResult(backend, picsureId, authorizationHeader, null);
    }

    /**
     * Fetches the result bytes of a stored query, upgrading a row stored before v3 first.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param picsureId the id of the stored query
     * @param authorizationHeader the caller's {@code Authorization} header
     * @param user the caller as the gateway identified them, or null when there is none; not read
     */
    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, String authorizationHeader, GatewayUser user) {
        StoredQuery stored = upgradeToV3(backend, load(picsureId), authorizationHeader);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        return hpds.queryResult(selector.select(backend), stored.resourceResultId());
    }

    public SignedUrlResponse queryResultSignedUrl(String backend, UUID picsureId) {
        return queryResultSignedUrl(backend, picsureId, null);
    }

    public SignedUrlResponse queryResultSignedUrl(String backend, UUID picsureId, String authorizationHeader) {
        return queryResultSignedUrl(backend, picsureId, authorizationHeader, null);
    }

    /**
     * Asks HPDS for a signed URL to the result of a stored query, upgrading a row stored before v3 first.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param picsureId the id of the stored query
     * @param authorizationHeader the caller's {@code Authorization} header
     * @param user the caller as the gateway identified them, or null when there is none; not read
     */
    public SignedUrlResponse queryResultSignedUrl(String backend, UUID picsureId, String authorizationHeader, GatewayUser user) {
        StoredQuery stored = upgradeToV3(backend, load(picsureId), authorizationHeader);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        return hpds.queryResultSignedUrl(selector.select(backend), stored.resourceResultId());
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

    public QueryStatus queryMetadata(UUID id) {
        return queryMetadata("open", id, null);
    }

    public QueryStatus queryMetadata(String backend, UUID id, String authorizationHeader) {
        if (id == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query id");
        }
        StoredQuery stored = load(id);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);

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
     * The stored-query body for the {@code /metadata} response. For a v3 row (or a null body) this is the raw parsed JSON, byte-for-byte as
     * before. For a v1 row it is the same {@code QueryRequest} wrapper with its nested {@code query} translated to the v3 shape, so clients
     * see one shape regardless of when the query was stored. Any translation failure falls back to the untranslated body (never an error);
     * a genuinely unparseable body still propagates {@link JsonProcessingException} to preserve the prior "queryJson absent" behavior.
     */
    Object buildQueryJson(StoredQuery stored) throws JsonProcessingException {
        String json = stored.query();
        if (json == null) {
            return null;
        }
        if (!isV3(stored)) {
            JsonNode root = parseOrNull(json);
            JsonNode translated = root == null ? null : tryTranslate(root);
            if (translated != null) {
                // Normalize to the same Map shape the v3/raw path returns, so queryJson has one type regardless of stored version.
                return MAPPER.convertValue(translated, Object.class);
            }
        }
        return MAPPER.readValue(json, Object.class);
    }

    /**
     * Attempts to translate a parsed stored v1 {@code QueryRequest} wrapper: deserialize its {@code query} node as a v1 {@code Query},
     * translate to v3, and re-embed it in {@code root}. Returns {@code null} when the body is not a wrapper object, has no object-valued
     * {@code query} node, or cannot be translated
     * ({@link edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException} or any Jackson error). Never throws.
     * {@link #buildQueryJson} then falls back to the raw body; {@link #upgradeToV3} rejects the row with 422.
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
