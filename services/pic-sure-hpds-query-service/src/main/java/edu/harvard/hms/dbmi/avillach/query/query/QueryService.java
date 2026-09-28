package edu.harvard.hms.dbmi.avillach.query.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
import edu.harvard.hms.dbmi.avillach.query.logging.LogValues;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.operations.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;
import edu.harvard.hms.dbmi.avillach.query.operations.UpdateQueryRequest;

/**
 * Implements the create, sync, status, result, signed-url, and metadata query lifecycle without a local database. Persistence goes through
 * {@link OperationsClient} over HTTP; operations-service generates each {@code picsureId} and is the sole query store.
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
    /**
     * JSON property names the v1 {@code Query} model deserializes, read from the model through Jackson so the set follows the class. A
     * stored pre-v3 {@code query} node that carries none of them is not a v1 query.
     */
    private static final Set<String> V1_QUERY_PROPERTIES = V1_QUERY_MAPPER.getDeserializationConfig()
        .introspect(V1_QUERY_MAPPER.constructType(edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.class)).findProperties().stream()
        .filter(BeanPropertyDefinition::couldDeserialize).map(BeanPropertyDefinition::getName).collect(Collectors.toUnmodifiableSet());
    /** Property names only the v3 {@code Query} carries. A stored pre-v3 {@code query} node holding any of them is not a v1 query. */
    private static final Set<String> V3_ONLY_QUERY_PROPERTIES =
        Set.of("phenotypicClause", "select", "authorizationFilters", "genomicFilters");

    private final OperationsClient operationsClient;
    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;
    private final ConsentAuthorizationService consentAuthorization;

    public QueryService(
        OperationsClient operationsClient, ResourceWebClient hpds, HpdsBackendSelector selector,
        ConsentAuthorizationService consentAuthorization
    ) {
        this.operationsClient = operationsClient;
        this.hpds = hpds;
        this.selector = selector;
        this.consentAuthorization = consentAuthorization;
    }

    public record QuerySyncResponse(byte[] body, String queryMetadata) {
    }

    // --- create / sync ---

    public QueryStatus query(String backend, QueryRequest req) {
        return query(backend, req, null);
    }

    public QueryStatus query(String backend, QueryRequest req, String authorizationHeader) {
        return create(backend, req, authorizationHeader);
    }

    private QueryStatus create(String backend, QueryRequest req, String authorizationHeader) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
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

    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource) {
        return querySync(backend, req, requestSource, null);
    }

    /**
     * Runs a query on HPDS's synchronous route and records it. HPDS returns its query id in the {@code queryMetadata} header for every
     * result type it computes as a query; INFO_COLUMN_LISTING is a listing rather than a query and carries no id, so nothing is recorded
     * for it. An HPDS rejection propagates from {@link ResourceWebClient} before anything is persisted.
     */
    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource, String authorizationHeader) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
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

    public QueryStatus queryStatus(String backend, UUID picsureId, QueryRequest req) {
        return queryStatus(backend, picsureId, req, null);
    }

    public QueryStatus queryStatus(String backend, UUID picsureId, QueryRequest req, String authorizationHeader) {
        StoredQuery stored = upgradeToV3(backend, load(picsureId), authorizationHeader);
        QueryStatus status = hpds.queryStatus(selector.select(backend), stored.resourceResultId(), req);
        status.setPicsureResultId(picsureId);
        operationsClient.update(picsureId, new UpdateQueryRequest(statusName(status.getStatus()), null, null, null, null));
        status.setResourceID(resourceUuidFromStored(stored));
        return status;
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, QueryRequest req) {
        return queryResult(backend, picsureId, req, null);
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, QueryRequest req, String authorizationHeader) {
        StoredQuery stored = upgradeToV3(backend, load(picsureId), authorizationHeader);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        return hpds.queryResult(selector.select(backend), stored.resourceResultId(), req);
    }

    public ResponseEntity<String> queryResultSignedUrl(String backend, UUID picsureId, QueryRequest req) {
        return queryResultSignedUrl(backend, picsureId, req, null);
    }

    public ResponseEntity<String> queryResultSignedUrl(String backend, UUID picsureId, QueryRequest req, String authorizationHeader) {
        StoredQuery stored = upgradeToV3(backend, load(picsureId), authorizationHeader);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        return hpds.queryResultSignedUrl(selector.select(backend), stored.resourceResultId(), req);
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
        logger.info(
            "Upgraded stored query {} (version {}) to version {}", stored.picsureId(), LogValues.of(stored.version()), CURRENT_VERSION
        );
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
        JsonNode translated = stored.query() == null || !hasV1QueryShape(stored.query()) ? null : tryTranslate(stored.query());
        if (translated != null) {
            try {
                return MAPPER.treeToValue(translated, QueryRequest.class);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                logger.warn("Unable to rebuild translated query {} as a QueryRequest: {}", stored.picsureId(), LogValues.of(e));
            }
        }
        throw new PicsureException(
            HttpStatus.UNPROCESSABLE_ENTITY, "untranslatable_query",
            "Query " + stored.picsureId() + " was stored in a format that cannot be converted to the current query format"
        );
    }

    /**
     * Returns whether the stored body's nested {@code query} object carries at least one v1 {@code Query} property and no v3-only property.
     * A body that does not parse, or has no object-valued {@code query}, returns {@code false}.
     */
    static boolean hasV1QueryShape(String json) {
        JsonNode queryNode;
        try {
            queryNode = MAPPER.readTree(json).get("query");
        } catch (JsonProcessingException e) {
            return false;
        }
        if (queryNode == null || !queryNode.isObject()) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        queryNode.fieldNames().forEachRemaining(keys::add);
        return keys.stream().noneMatch(V3_ONLY_QUERY_PROPERTIES::contains) && keys.stream().anyMatch(V1_QUERY_PROPERTIES::contains);
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
            logger.warn("Unable to read stored query/metadata for {}: {}", id, LogValues.of(e));
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
            JsonNode translated = tryTranslate(json);
            if (translated != null) {
                // Normalize to the same Map shape the v3/raw path returns, so queryJson has one type regardless of stored version.
                return MAPPER.convertValue(translated, Object.class);
            }
        }
        return MAPPER.readValue(json, Object.class);
    }

    /**
     * Attempts to translate a stored v1 {@code QueryRequest} wrapper: parse it, deserialize its {@code query} node as a v1 {@code Query},
     * translate to v3, and re-embed. Returns {@code null} when the body is not a wrapper object, has no object-valued {@code query} node,
     * or cannot be translated ({@link edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException} or any
     * Jackson error). Never throws. {@link #buildQueryJson} then falls back to the raw body; {@link #upgradeToV3} rejects the row with 422.
     */
    JsonNode tryTranslate(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
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
            logger.warn("Unable to translate stored v1 query to v3: {}", LogValues.of(e));
            return null;
        }
    }
}
