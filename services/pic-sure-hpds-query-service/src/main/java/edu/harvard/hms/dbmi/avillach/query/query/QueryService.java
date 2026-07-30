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

import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryRequest;
import edu.harvard.dbmi.avillach.contracts.internal.StoredQuery;
import edu.harvard.dbmi.avillach.contracts.internal.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Ports the legacy WAR's {@code PicsureQueryService} (create/sync/status/result/signed-url/metadata) into a DB-free service: every place
 * the legacy code read/wrote a local {@code Query} JPA entity, this class instead calls {@link OperationsClient} over HTTP. There is no
 * local UUID generation and no local {@code Query} entity anywhere in this module -- operations-service is the sole source of the {@code
 * picsureId} and the sole persistence store.
 *
 * <p><b>Typed on both sides:</b> the public entry points take the bare v3 {@link Query} (or nothing but the query id, for reads) and return
 * {@link QueryStatusResponse}/{@link SignedUrlResponse}, and the HPDS hop underneath speaks the same records. The aggregate (open) path
 * shares {@link #queryV3(String, Query)} rather than carrying an envelope of its own. The {@code QueryRequest} wrapper survives ONLY inside
 * {@link #storedBody(Query)}, i.e. in the persisted blob, until Task 15 migrates it. The store DTOs are the shared
 * {@code edu.harvard.dbmi.avillach.contracts.internal} records, so a status crossing this service is the {@link PicSureStatus} enum end to
 * end rather than a string that only happened to parse.
 *
 * <p><b>Decision 9 (the signed-url bug fix):</b> {@link #status}, {@link #result}, and {@link #signedUrl} all dispatch to HPDS using the
 * backend implied by the ingress {@code {backend}} path segment (auth/open) AND the v3-ness of the STORED query's {@code version} field
 * (never a value passed in on the request). The legacy WAR applied this "stored version decides v1 vs v3" rule to status and result, but
 * not to signed-url ({@code PicsureQueryService.java:197+}) -- a v1-path signed-url request for a v3-stored query never reached HPDS's
 * {@code /v3} routes. Here all three read ops share the same {@link #isV3(StoredQuery)} check, closing that gap.
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

    // --- create / sync ---

    /** Typed v3 path: a bare {@link Query} in, the same bare {@link Query} out to HPDS, {@link QueryStatusResponse} back. */
    public QueryStatusResponse queryV3(String backend, Query query) {
        requireQuery(query);
        HpdsTarget target = selector.select(backend, true); // URL + service token
        // HPDS call first (parity: query() calls HPDS then persists)
        QueryStatusResponse down = requireStatus(hpds.query(target, query));
        return persistCreate(down, storedBody(query), CURRENT_VERSION);
    }

    private static void requireQuery(Query query) {
        if (query == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
    }

    /**
     * Persists a freshly dispatched query and stamps the store's {@code picsureId} onto the response. Preserves the WAR's create-time
     * fallback: when the resource returned no id of its own, the {@code picsureId} becomes the {@code resourceResultId}.
     */
    private QueryStatusResponse persistCreate(QueryStatusResponse down, String queryJson, String version) {
        Map<String, Object> resultMetadata = down.resultMetadata(); // the record normalizes null to an empty map
        UUID picsureId = operationsClient
            .save(new SaveQueryRequest(queryJson, down.resourceResultId(), down.status(), version, encodeMetadata(resultMetadata)));

        String resourceResultId = down.resourceResultId();
        if (resourceResultId == null) { // create-time fallback (PRESERVE)
            resourceResultId = picsureId.toString();
            operationsClient.update(picsureId, new UpdateQueryRequest(null, resourceResultId, null));
        }
        return new QueryStatusResponse(
            picsureId, down.status(), down.resourceStatus(), resourceResultId, down.sizeInBytes(), down.startTime(), down.duration(),
            down.expiration(), resultMetadata
        );
    }

    public QuerySyncResponse querySync(String backend, Query query, String requestSource) {
        requireQuery(query);
        HpdsTarget target = selector.select(backend, true); // sync's only remaining caller is the v3 ingress

        // persist FIRST (parity: sync persists then calls HPDS)
        UUID picsureId = operationsClient.save(new SaveQueryRequest(storedBody(query), null, null, CURRENT_VERSION, null));

        ResourceWebClient.QuerySyncResult down = hpds.querySync(target, query, requestSource);
        String resourceResultId = down.queryMetadata() != null ? down.queryMetadata() : picsureId.toString();
        operationsClient.update(picsureId, new UpdateQueryRequest(null, resourceResultId, null));

        return new QuerySyncResponse(down.body(), down.queryMetadata());
    }

    /** Port of copyQuery's metadata assembly (PicsureQueryService.java:380-419) minus Resource + AuditContext. */
    private String encodeMetadata(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
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
     * The blob handed to the query store. Still the {@code {"query": ...}} WRAPPER rather than the bare query, even though the HPDS hop is
     * now bare: operations-service's {@code /dispatch} endpoint strips {@code resourceCredentials} out of exactly this wrapper for the
     * gateway's {@code QueryAuthFetcher}, and {@link #metadata} reads stored v1 rows back through it. Rewriting the persisted shape is a
     * data migration, not a wire change.
     *
     * TODO(well-defined-contracts): Task 15 migrates the stored blob to the bare v3 Query.
     */
    private String storedBody(Query query) {
        return storedBody(new GeneralQueryRequest().setQuery(query));
    }

    /**
     * null query -&gt; null blob; else the serialized full QueryRequest (including resourceCredentials -- stripped only at dispatch time).
     */
    private String storedBody(QueryRequest req) {
        if (req.getQuery() == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Incorrectly formatted request");
        }
    }

    // --- read ops with uniform stored-version dispatch ---

    public QueryStatusResponse status(String backend, UUID picsureId) {
        StoredQuery stored = load(picsureId);
        HpdsTarget target = selector.select(backend, isV3(stored)); // backend from path, version from the stored row
        QueryStatusResponse down = requireStatus(hpds.queryStatus(target, stored.resourceResultId()));
        operationsClient.update(picsureId, new UpdateQueryRequest(down.status(), null, null));
        return new QueryStatusResponse(
            picsureId, down.status(), down.resourceStatus(), down.resourceResultId(), down.sizeInBytes(), down.startTime(), down.duration(),
            down.expiration(), down.resultMetadata()
        );
    }

    public ResponseEntity<byte[]> result(String backend, UUID picsureId) {
        StoredQuery stored = load(picsureId);
        return hpds.queryResult(selector.select(backend, isV3(stored)), stored.resourceResultId());
    }

    /**
     * HPDS answers signed-url with {@code {"signedUrl": "..."}}. Anything else is an upstream contract violation and surfaces as a 502
     * rather than a 200 carrying a null url, which a caller would otherwise have to discover by following it.
     */
    public SignedUrlResponse signedUrl(String backend, UUID picsureId) {
        StoredQuery stored = load(picsureId);
        // DECISION 9 FIX: dispatch on STORED version for signed-url too (the legacy WAR omitted this).
        SignedUrlResponse down = hpds.queryResultSignedUrl(selector.select(backend, isV3(stored)), stored.resourceResultId());
        if (down == null || down.signedUrl() == null) {
            throw new HpdsCommunicationException("HPDS signed-url response carried no signedUrl");
        }
        return down;
    }

    /** A 2xx with no readable status body is an upstream contract violation, not a query we can go on to persist. */
    private static QueryStatusResponse requireStatus(QueryStatusResponse down) {
        if (down == null) {
            throw new HpdsCommunicationException("HPDS returned no query status");
        }
        return down;
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

    // --- metadata (DB-only, no HPDS) ---

    public QueryStatusResponse metadata(UUID id) {
        if (id == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query id");
        }
        StoredQuery stored = load(id);

        Map<String, Object> resultMetadata = new HashMap<>();
        try {
            resultMetadata.put("queryJson", buildQueryJson(stored));
            resultMetadata.put("queryResultMetadata", decodeMetadata(stored.metadata()));
        } catch (JsonProcessingException e) {
            logger.warn("Unable to read stored query/metadata for {}", id, e);
        }

        return new QueryStatusResponse(
            stored.picsureId(), stored.status(), null, stored.resourceResultId(), 0L, 0L, 0L, 0L, resultMetadata
        );
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
            Query v3 = QueryTranslator.translate(v1);
            wrapper.set("query", MAPPER.valueToTree(v3));
            return wrapper;
        } catch (Exception e) {
            logger.warn("Unable to translate stored v1 query to v3; returning it untranslated", e);
            return null;
        }
    }
}
