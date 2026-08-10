package edu.harvard.hms.dbmi.avillach.operations.query;

import java.sql.Date;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryRequest;
import edu.harvard.dbmi.avillach.contracts.internal.StoredQuery;
import edu.harvard.dbmi.avillach.contracts.internal.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * The sole read/write path onto the {@code query} table for the internal query API ({@link InternalQueryController}).
 * Persists/loads/updates {@link Query} rows and produces the gateway-only dispatch payload.
 *
 * <p>The stored blob is OPAQUE to this service on the write path: whatever the caller sends is what gets gzipped into the column. Since
 * Task 15 the query-service writes the BARE canonical v3 {@code Query} JSON there; rows written before that carry the legacy
 * {@code QueryRequest} envelope ({@code {"@type":..., "resourceCredentials":{}, "query":{...}, "resourceUUID":null}}).
 *
 * <p><b>Dispatch normalizes both shapes to the bare query</b> -- see {@link #dispatchQueryJson(UUID)}. That is a deliberate security
 * decision, not a convenience: the dispatch payload becomes {@code TargetedRequest.query} in the gateway's PSAMA introspection for the
 * bodyless reads ({@code /query/{id}/result}, {@code /signed-url}, {@code /status}, {@code /metadata}), and the deployed JsonPath
 * authorization rules must not have to know how old a row is. Normalizing here means an introspected READ sees {@code $.query.<field>}
 * exactly like an introspected SUBMIT does, whatever the row's age.
 *
 * <p><b>{@link #get(UUID)} strips credentials too</b>, without normalizing the shape -- see {@link #toDto(Query)}.
 *
 * <p>{@code status} travels on the wire as the {@link PicSureStatus} enum NAME, never its ordinal, and is now stored that way too
 * ({@code @Enumerated(EnumType.STRING)}). {@code metadata} travels as base64-encoded bytes, matching the entity's raw {@code byte[]}
 * column; malformed base64 is a caller error (400), not a 500.
 */
@Service
public class QueryPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(QueryPersistenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueryRepository repo;

    public QueryPersistenceService(QueryRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public UUID save(SaveQueryRequest req) {
        Query entity = new Query();
        entity.setQuery(stripResourceCredentials(req.query()));
        entity.setResourceResultId(req.resourceResultId());
        entity.setStatus(req.status());
        entity.setVersion(req.version());
        entity.setMetadata(decodeMetadata(req.metadata()));
        entity.setStartTime(new Date(System.currentTimeMillis())); // server-owned, like the legacy WAR's create path
        return repo.save(entity).getUuid();
    }

    @Transactional(readOnly = true)
    public StoredQuery get(UUID picsureId) {
        return toDto(load(picsureId));
    }

    @Transactional
    public void update(UUID picsureId, UpdateQueryRequest req) {
        Query entity = load(picsureId);
        if (req.status() != null) {
            // FIRST transition to AVAILABLE only: re-reporting AVAILABLE must not move the timestamp, or a
            // client that polls after completion would keep pushing readyTime forward. No parseStatus hop --
            // UpdateQueryRequest.status() is already a typed PicSureStatus on this branch.
            if (req.status() == PicSureStatus.AVAILABLE && entity.getReadyTime() == null) {
                entity.setReadyTime(new Date(System.currentTimeMillis()));
            }
            entity.setStatus(req.status());
        }
        if (req.resourceResultId() != null) {
            entity.setResourceResultId(req.resourceResultId());
        }
        if (req.metadata() != null) {
            entity.setMetadata(decodeMetadata(req.metadata()));
        }
        repo.save(entity);
    }

    /**
     * Gateway-only auth-fetch payload: the stored query, normalized to the BARE query JSON and re-serialized as a STRING.
     *
     * <ul> <li>A row written since Task 15 already holds the bare v3 {@code Query} -- returned as-is.</li> <li>An older row holds the
     * {@code QueryRequest} envelope -- its {@code query} member is unwrapped so the caller sees the same node shape as a new row (see the
     * class javadoc for why that uniformity is load-bearing for authorization).</li> <li>{@code resourceCredentials} is removed either way,
     * at the envelope root AND on the unwrapped node -- old rows really do carry secrets there, and a dispatch payload must never return
     * them.</li> </ul>
     *
     * <p>404 when the row is absent (the gateway's {@code QueryAuthFetcher} fails closed on that). A blank/absent stored query yields
     * {@code null} (never a 500) -- malformed JSON is logged and also yields {@code null} rather than leaking the raw unparsed body.
     */
    @Transactional(readOnly = true)
    public String dispatchQueryJson(UUID picsureId) {
        Query entity = load(picsureId);
        String json = entity.getQuery();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(bareQuery(MAPPER.readTree(json)));
        } catch (JsonProcessingException e) {
            LOG.warn("Stored query for {} is not valid JSON", picsureId, e);
            return null;
        }
    }

    /**
     * Envelope-tolerant unwrap: a root whose {@code query} member is an OBJECT is the legacy envelope; anything else is already the bare
     * query.
     *
     * <p>The {@code isObject()} test (rather than a mere null check) matters twice: a legacy row storing {@code "query":null} would
     * otherwise unwrap to a {@code NullNode} and dispatch would hand the gateway the literal string {@code "null"}, and a row storing a
     * non-object {@code query} (e.g. {@code "query":"q"}) is not a query to unwrap at all -- both cases keep the root, with credentials
     * stripped.
     */
    private static JsonNode bareQuery(JsonNode root) {
        JsonNode query = root;
        if (root instanceof ObjectNode envelope) {
            envelope.remove("resourceCredentials"); // SECURITY: never return stored credentials
            JsonNode nested = envelope.get("query");
            if (nested != null && nested.isObject()) {
                query = nested;
            }
        }
        if (query instanceof ObjectNode bare) {
            bare.remove("resourceCredentials"); // SECURITY: defensive -- credentials nested inside the envelope's query
        }
        return query;
    }

    private Query load(UUID picsureId) {
        return repo.findById(picsureId).orElseThrow(() -> notFound(picsureId));
    }

    /**
     * The {@code GET /internal/queries/{id}} payload. {@code InternalTokenFilter} is the gate on that endpoint; this is defense in depth
     * behind it -- legacy envelope rows really do carry {@code resourceCredentials}, and a gate failure must not be able to hand a stored
     * bearer token back out. Same secret removal the dispatch path performs.
     *
     * <p>Unlike dispatch, the stored SHAPE is preserved: the only consumer is query-service's
     * {@code QueryService#buildQueryJson}/{@code tryTranslate}, which does its own envelope-tolerant unwrap and decides v1-vs-v3
     * translation from what it receives. Normalizing here would silently change that decision; removing credentials cannot.
     *
     * <p>An unparseable stored body has no tree to strip, so it is dropped ({@code null}) rather than returned raw -- the raw bytes may
     * still spell out a secret. That matches {@link #dispatchQueryJson(UUID)}'s posture, and query-service already renders an absent stored
     * query as an absent {@code queryJson}.
     */
    private static StoredQuery toDto(Query entity) {
        return new StoredQuery(
            entity.getUuid(), withoutCredentials(entity.getQuery(), entity.getUuid()), entity.getResourceResultId(), entity.getStatus(),
            entity.getVersion(), encodeMetadata(entity.getMetadata()), toEpochMillis(entity.getStartTime()),
            toEpochMillis(entity.getReadyTime())
        );
    }

    /**
     * Epoch millis off the entity's legacy {@code DATE} columns; null stays null. The columns are a storage detail -- the wire carries
     * numbers so no client has to agree with the store on a date format.
     */
    private static Long toEpochMillis(Date date) {
        return date == null ? null : date.getTime();
    }

    /**
     * SECURITY, write side (mainline #277): {@code resourceCredentials} must never reach the table. Writers already strip before sending,
     * so this is defence in depth against a writer that forgets -- credentials that land here would sit at rest and echo back through
     * {@code /metadata}'s queryJson.
     *
     * <p><b>Deliberately more lenient than {@link #withoutCredentials(String, UUID)}</b>, which serves the READ path. Here an unparseable
     * body passes through unchanged: it cannot carry a parseable credentials field, and refusing to persist it would fail the write
     * outright over a body the caller may legitimately own. On the read path the same input is dropped to {@code null} instead, because raw
     * bytes we cannot parse may still spell out a secret and no reader needs them. Same field, opposite failure postures, on purpose.
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

    private static String withoutCredentials(String json, UUID picsureId) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            stripCredentials(root);
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            LOG.warn("Stored query for {} is not valid JSON; omitting it rather than returning it unstripped", picsureId, e);
            return null;
        }
    }

    /** Removes every {@code resourceCredentials} member anywhere in the tree, in place. */
    private static void stripCredentials(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("resourceCredentials");
            object.forEach(QueryPersistenceService::stripCredentials);
        } else if (node != null && node.isArray()) {
            node.forEach(QueryPersistenceService::stripCredentials);
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
