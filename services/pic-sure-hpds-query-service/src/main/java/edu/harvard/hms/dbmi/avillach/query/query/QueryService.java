package edu.harvard.hms.dbmi.avillach.query.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
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
 * <p>{@link #queryStatus}, {@link #queryResult}, and {@link #queryResultSignedUrl} dispatch to the backend selected by the ingress
 * {@code {backend}} segment and the stored query's {@code version}. The request cannot override the stored version, and all three read
 * operations share the same {@link #isV3(StoredQuery)} check.
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

    /** Result types HPDS serves only through the asynchronous submit/status/result flow. */
    private static final Set<String> ASYNC_ONLY_RESULT_TYPES = Set.of("DATAFRAME", "DATAFRAME_TIMESERIES", "PATIENTS");

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
        return create(backend, req, false, null);
    }

    public QueryStatus queryV3(String backend, QueryRequest req) {
        return queryV3(backend, req, null);
    }

    public QueryStatus queryV3(String backend, QueryRequest req, String authorizationHeader) {
        return create(backend, req, true, authorizationHeader);
    }

    private QueryStatus create(String backend, QueryRequest req, boolean v3, String authorizationHeader) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        consentAuthorization.scopeQuery(backend, req, authorizationHeader);
        HpdsTarget target = selector.select(backend, v3); // URL + service token

        QueryStatus results = hpds.query(target, req); // Call HPDS before persisting the query.
        String version = v3 ? CURRENT_VERSION : null;
        String metadataBase64 = buildMetadataBase64(results);

        UUID picsureId = operationsClient.save(
            new SaveQueryRequest(
                serializeQuery(req), results.getResourceResultId(), statusName(results.getStatus()), version, metadataBase64
            )
        );
        results.setPicsureResultId(picsureId);

        if (results.getResourceResultId() == null) { // Use the generated PIC-SURE id when HPDS omits its result id.
            String fallbackId = picsureId.toString();
            results.setResourceResultId(fallbackId);
            operationsClient.update(picsureId, new UpdateQueryRequest(null, fallbackId, null));
        }
        results.setResourceID(req.getResourceUUID()); // echo (no Resource entity)
        return results;
    }

    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource) {
        return querySync(backend, req, requestSource, null);
    }

    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource, String authorizationHeader) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        rejectAsyncOnlyResultType(req);
        consentAuthorization.scopeQuery(backend, req, authorizationHeader);
        HpdsTarget target = selector.select(backend, true); // sync's only remaining caller is the v3 ingress
        String version = CURRENT_VERSION;

        // Persist before calling HPDS so the sync query has a PIC-SURE id.
        UUID picsureId = operationsClient.save(new SaveQueryRequest(serializeQuery(req), null, null, version, null));

        ResourceWebClient.QuerySyncResult down = hpds.querySync(target, req, requestSource);
        String resourceResultId = down.queryMetadata() != null ? down.queryMetadata() : picsureId.toString();
        operationsClient.update(picsureId, new UpdateQueryRequest(null, resourceResultId, null));

        return new QuerySyncResponse(down.body(), down.queryMetadata());
    }

    /**
     * Result types HPDS backs with an asynchronous job are not served on {@code /query/sync}: the caller submits with {@code POST /query},
     * polls {@code /query/{id}/status}, then collects {@code /query/{id}/result}, which is what the frontend export flow and the Python
     * adapter's PFB export already do.
     *
     * <p>HPDS answers 400 for these on its own sync route, but {@link ResourceWebClient} turns any 4xx into an
     * {@code HpdsCommunicationException}, which would reach the caller as an opaque upstream failure. Rejecting here keeps the explanation,
     * and rejecting before {@code operationsClient.save} avoids persisting a query row for a request that cannot be served.
     *
     * @param req the incoming query request
     * @throws PicsureException with status 400 when the requested result type is asynchronous-only
     */
    private static void rejectAsyncOnlyResultType(QueryRequest req) {
        String resultType = expectedResultType(req);
        if (resultType != null && ASYNC_ONLY_RESULT_TYPES.contains(resultType)) {
            throw new PicsureException(
                HttpStatus.BAD_REQUEST, "bad_request",
                "Result type " + resultType + " is served asynchronously. Submit it with POST /query, poll "
                    + "/query/{resourceQueryId}/status, then collect it from /query/{resourceQueryId}/result."
            );
        }
    }

    /**
     * Reads {@code expectedResultType} out of the untyped query body, which arrives either as a map or as a JSON string.
     *
     * @param req the incoming query request
     * @return the upper-cased result type, or null when the body carries none
     */
    private static String expectedResultType(QueryRequest req) {
        Object query = req.getQuery();
        if (query == null) {
            return null;
        }
        JsonNode node;
        if (query instanceof String text) {
            try {
                node = MAPPER.readTree(text);
            } catch (JsonProcessingException e) {
                return null;
            }
        } else {
            node = MAPPER.valueToTree(query);
        }
        JsonNode resultType = node.get("expectedResultType");
        return resultType == null || resultType.isNull() ? null : resultType.asText().toUpperCase(Locale.ENGLISH);
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
     * null query → null blob; else the serialized QueryRequest with {@code resourceCredentials} removed. Credentials are only ever needed
     * for the live HPDS call (which uses the in-memory request); nothing reads them back from operations-service (dispatch re-strips as
     * defense in depth), so they must never reach the persistence store or the /metadata queryJson echo.
     */
    private String serializeQuery(QueryRequest req) {
        if (req.getQuery() == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.valueToTree(req);
            if (node instanceof ObjectNode obj) {
                obj.remove("resourceCredentials");
            }
            return MAPPER.writeValueAsString(node);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Incorrectly formatted request");
        }
    }

    private static String statusName(PicSureStatus status) {
        return status == null ? null : status.name();
    }

    // --- read ops with uniform stored-version dispatch ---

    public QueryStatus queryStatus(String backend, UUID picsureId, QueryRequest req) {
        StoredQuery stored = load(picsureId);
        HpdsTarget target = selector.select(backend, isV3(stored)); // backend from path, version from the stored row
        QueryStatus status = hpds.queryStatus(target, stored.resourceResultId(), req);
        status.setPicsureResultId(picsureId);
        operationsClient.update(picsureId, new UpdateQueryRequest(statusName(status.getStatus()), null, null));
        status.setResourceID(resourceUuidFromStored(stored));
        return status;
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, QueryRequest req) {
        return queryResult(backend, picsureId, req, null);
    }

    public ResponseEntity<byte[]> queryResult(String backend, UUID picsureId, QueryRequest req, String authorizationHeader) {
        StoredQuery stored = load(picsureId);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        return hpds.queryResult(selector.select(backend, isV3(stored)), stored.resourceResultId(), req);
    }

    public ResponseEntity<String> queryResultSignedUrl(String backend, UUID picsureId, QueryRequest req) {
        return queryResultSignedUrl(backend, picsureId, req, null);
    }

    public ResponseEntity<String> queryResultSignedUrl(String backend, UUID picsureId, QueryRequest req, String authorizationHeader) {
        StoredQuery stored = load(picsureId);
        consentAuthorization.verifyReadAccess(backend, stored, authorizationHeader);
        // Dispatch signed-url requests using the stored query version.
        return hpds.queryResultSignedUrl(selector.select(backend, isV3(stored)), stored.resourceResultId(), req);
    }

    private StoredQuery load(UUID picsureId) {
        if (picsureId == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query id");
        }
        return operationsClient.get(picsureId); // throws PicsureException(NOT_FOUND) on an unknown id
    }

    /** Returns whether the stored query's major version is 3. */
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
     * translate to v3, and re-embed. Returns {@code null} (caller falls back to the raw body) when the body is not a wrapper object, has no
     * object-valued {@code query} node, or cannot be translated
     * ({@link edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException} or any Jackson error). Never throws.
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
            logger.warn("Unable to translate stored v1 query to v3; returning it untranslated", e);
            return null;
        }
    }
}
