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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.FederatedQueryRequest;
import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
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
    private static final String CURRENT_VERSION = "3";

    private final OperationsClient operationsClient;
    private final ResourceWebClient hpds;
    private final HpdsBackendSelector selector;
    private final SiteParsingService sites;

    public QueryService(OperationsClient operationsClient, ResourceWebClient hpds, HpdsBackendSelector selector, SiteParsingService sites) {
        this.operationsClient = operationsClient;
        this.hpds = hpds;
        this.selector = selector;
        this.sites = sites;
    }

    public record QuerySyncResponse(byte[] body, String queryMetadata) {
    }

    // --- create / sync ---

    public QueryStatus query(String backend, QueryRequest req) {
        return create(backend, req, false);
    }

    public QueryStatus queryV3(String backend, QueryRequest req) {
        return create(backend, req, true);
    }

    public QueryStatus institutionalQuery(String backend, FederatedQueryRequest req, String email, boolean v3) {
        String siteCode = sites.parseSiteOfOrigin(email).orElse("Unknown");
        req.setInstitutionOfOrigin(siteCode);
        req.setRequesterEmail(email);
        return create(backend, req, v3);
    }

    private QueryStatus create(String backend, QueryRequest req, boolean v3) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        HpdsTarget target = selector.select(backend, v3); // URL + service token

        QueryStatus results = hpds.query(target, req); // HPDS call first (parity: query() calls HPDS then persists)
        String version = v3 ? CURRENT_VERSION : null;
        String metadataBase64 = buildMetadataBase64(req, results);

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

    public QuerySyncResponse querySync(String backend, QueryRequest req, String requestSource, boolean v3) {
        if (req == null) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Missing query data");
        }
        HpdsTarget target = selector.select(backend, v3);
        String version = v3 ? CURRENT_VERSION : null;

        // persist FIRST (parity: sync persists then calls HPDS)
        UUID picsureId = operationsClient.save(new SaveQueryRequest(serializeQuery(req), null, null, version, null));

        ResourceWebClient.QuerySyncResult down = hpds.querySync(target, req, requestSource);
        String resourceResultId = down.queryMetadata() != null ? down.queryMetadata() : picsureId.toString();
        operationsClient.update(picsureId, new UpdateQueryRequest(null, resourceResultId, null));

        return new QuerySyncResponse(down.body(), down.queryMetadata());
    }

    /** Port of copyQuery's metadata assembly (PicsureQueryService.java:380-419) minus Resource + AuditContext. */
    private String buildMetadataBase64(QueryRequest req, QueryStatus response) {
        Map<String, Object> meta = response.getResultMetadata();
        if (meta == null) {
            meta = new HashMap<>();
        }
        if (req instanceof FederatedQueryRequest gic) {
            meta.put("commonAreaUUID", gic.getCommonAreaUUID());
            meta.put("site", gic.getInstitutionOfOrigin());
            // DataSharingStatus.Unknown by name: this module has no dependency on pic-sure-api-data's entity package
            // (DB-free), so the GIC sharing-status marker is carried as its enum-name string instead of the enum type.
            meta.put("sharingStatus", "Unknown");
            meta.put("requesterEmail", gic.getRequesterEmail());
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
        // DECISION 9 FIX: dispatch on STORED version for signed-url too (the legacy WAR omitted this).
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
        StoredQuery stored = loadForMetadata(id);

        QueryStatus response = new QueryStatus();
        response.setPicsureResultId(stored.picsureId());
        response.setResourceID(resourceUuidFromStored(stored));
        response.setStatus(stored.status() == null ? null : PicSureStatus.valueOf(stored.status()));
        response.setResourceResultId(stored.resourceResultId());

        Map<String, Object> metadata = new HashMap<>();
        try {
            metadata.put("queryJson", stored.query() == null ? null : MAPPER.readValue(stored.query(), Object.class));
            metadata.put("queryResultMetadata", decodeMetadata(stored.metadata()));
        } catch (JsonProcessingException e) {
            logger.warn("Unable to read stored query/metadata for {}", id, e);
        }
        response.setResultMetadata(metadata);
        return response;
    }

    /** Accepts EITHER a picsureId or a GIC commonAreaUUID, matching the legacy queryMetadata's dual lookup. */
    private StoredQuery loadForMetadata(UUID id) {
        try {
            return operationsClient.get(id);
        } catch (PicsureException primary) {
            if (primary.getStatus() != HttpStatus.NOT_FOUND) {
                throw primary;
            }
            StoredQuery byCommonArea;
            try {
                byCommonArea = operationsClient.findByCommonAreaUUID(id);
            } catch (PicsureException fallbackFailure) {
                throw primary; // surface the original, clean NOT_FOUND rather than the fallback call's error shape
            }
            if (byCommonArea == null) {
                throw primary;
            }
            return byCommonArea;
        }
    }

    private static String decodeMetadata(String base64Metadata) {
        if (base64Metadata == null) {
            return null;
        }
        return new String(Base64.getDecoder().decode(base64Metadata), StandardCharsets.UTF_8);
    }
}
