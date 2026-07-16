package edu.harvard.hms.dbmi.avillach.query.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.operations.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;
import edu.harvard.hms.dbmi.avillach.query.operations.UpdateQueryRequest;

/**
 * Ports the legacy WAR's {@code PicsureQueryService} (create/sync/status/result/signed-url/metadata) into a DB-free service: every place
 * the legacy code read/wrote a local {@code Query} JPA entity, this class instead calls {@link OperationsClient} over HTTP. There is no
 * local UUID generation and no local {@code Query} entity anywhere in this module -- operations-service is the sole source of the {@code
 * picsureId} and the sole persistence store.
 *
 * <p><b>Decision 9 (the signed-url bug fix):</b> {@link #queryStatus}, {@link #queryResult}, and {@link #queryResultSignedUrl} all dispatch
 * to HPDS using the backend implied by the ingress {@code {backend}} path segment (auth/open) AND the v3-ness of the STORED query's {@code
 * version} field (never a value passed in on the request). The legacy WAR applied this "stored version decides v1 vs v3" rule to status and
 * result, but not to signed-url ({@code PicsureQueryService.java:197+}) -- a v1-path signed-url request for a v3-stored query never reached
 * HPDS's {@code /v3} routes. Here all three read ops share the same {@link #isV3(StoredQuery)} check, closing that gap.
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

    private final OperationsClient operationsClient;
    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;

    public QueryService(OperationsClient operationsClient, ResourceWebClient hpds, HpdsBackendSelector selector) {
        this.operationsClient = operationsClient;
        this.hpds = hpds;
        this.selector = selector;
    }

    public record QuerySyncResponse(byte[] body, String queryMetadata) {
    }


    public QueryStatus query(String backend, QueryRequest req) {
        return create(backend, req, false);
    }

    public QueryStatus queryV3(String backend, QueryRequest req) {
        return create(backend, req, true);
    }

    private QueryStatus create(String backend, QueryRequest req, boolean v3) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        HpdsTarget target = selector.select(backend, v3); // URL + service token

        QueryStatus results = hpds.query(target, req); // HPDS call first (parity: query() calls HPDS then persists)
        String version = v3 ? CURRENT_VERSION : null;
        String metadataBase64 = buildMetadataBase64(results);

        UUID picsureId = operationsClient.save(
            new SaveQueryRequest(
                serializeQuery(req), results.getResourceResultId(), statusName(results.getStatus()), version, metadataBase64
            )
        );
        results.setPicsureResultId(picsureId);

        if (results.getResourceResultId() == null) { // create-time fallback (PRESERVE)
            String fallbackId = picsureId.toString();
            results.setResourceResultId(fallbackId);
            operationsClient.update(picsureId, new UpdateQueryRequest(null, fallbackId, null));
        }
        results.setResourceID(req.getResourceUUID()); // echo (no Resource entity)
        return results;
    }

    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        HpdsTarget target = selector.select(backend, true); // sync's only remaining caller is the v3 ingress
        String version = CURRENT_VERSION;

        // persist FIRST (parity: sync persists then calls HPDS)
        UUID picsureId = operationsClient.save(new SaveQueryRequest(serializeQuery(req), null, null, version, null));

        ResourceWebClient.QuerySyncResult down = hpds.querySync(target, req, requestSource);
        String resourceResultId = down.queryMetadata() != null ? down.queryMetadata() : picsureId.toString();
        operationsClient.update(picsureId, new UpdateQueryRequest(null, resourceResultId, null));

        return new QuerySyncResponse(down.body(), down.queryMetadata());
    }

    /** Port of copyQuery's metadata assembly (PicsureQueryService.java:380-419) minus Resource + AuditContext. */
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

    /** null query → null blob; else the serialized full QueryRequest (including resourceCredentials -- stripped only at dispatch time). */
    private String serializeQuery(QueryRequest req) {
        if (req.getQuery() == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(req);
        } catch (JsonProcessingException e) {
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
        StoredQuery stored = load(picsureId);
        return hpds.queryResult(selector.select(backend, isV3(stored)), stored.resourceResultId(), req);
    }

    public ResponseEntity<String> queryResultSignedUrl(String backend, UUID picsureId, QueryRequest req) {
        StoredQuery stored = load(picsureId);
        return hpds.queryResultSignedUrl(selector.select(backend, isV3(stored)), stored.resourceResultId(), req);
    }

    private StoredQuery load(UUID picsureId) {
        if (picsureId == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query id");
        }
        return operationsClient.get(picsureId); // throws PicsureException(NOT_FOUND) on an unknown id
    }

    /** Preserves PicsureQueryService.isV3Query: version major == "3" (null-safe). */
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
