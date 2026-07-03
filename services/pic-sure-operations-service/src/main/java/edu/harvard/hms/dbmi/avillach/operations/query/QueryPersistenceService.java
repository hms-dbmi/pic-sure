package edu.harvard.hms.dbmi.avillach.operations.query;

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

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.data.entity.Query;
import edu.harvard.hms.dbmi.avillach.data.repository.QueryRepository;

/**
 * The sole read/write path onto the {@code query} table for the internal query API ({@link InternalQueryController}).
 * Persists/loads/updates {@link Query} rows and produces the gateway-only dispatch payload -- the stored query JSON with
 * {@code resourceCredentials} stripped, per the fixed {@code QueryAuthFetcher} contract ({@code {queryJson: "<string>"}}).
 *
 * <p>{@code status} travels on the wire as the {@link PicSureStatus} enum NAME, never its ordinal, so callers never need the enum type; an
 * unrecognized name is a caller error (400), not a 500. {@code metadata} travels as base64-encoded bytes, matching the entity's raw
 * {@code byte[]} column.
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
        entity.setQuery(req.query());
        entity.setResourceResultId(req.resourceResultId());
        entity.setStatus(parseStatus(req.status()));
        entity.setVersion(req.version());
        entity.setMetadata(decodeMetadata(req.metadata()));
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
            entity.setStatus(parseStatus(req.status()));
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
     * Gateway-only auth-fetch payload: the stored query JSON, re-serialized as a STRING with {@code resourceCredentials} removed. 404 when
     * the row is absent (the gateway's {@code QueryAuthFetcher} fails closed on that). A blank/absent stored query body yields {@code null}
     * (never a 500) -- malformed JSON is logged and also yields {@code null} rather than leaking the raw unparsed body.
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

    @Transactional(readOnly = true)
    public StoredQuery findByCommonAreaUUID(UUID commonAreaUUID) {
        Query entity = repo.getQueryUUIDFromCommonAreaUUID(commonAreaUUID);
        if (entity == null) {
            throw notFound(commonAreaUUID);
        }
        return toDto(entity);
    }

    private Query load(UUID picsureId) {
        return repo.findById(picsureId).orElseThrow(() -> notFound(picsureId));
    }

    private static StoredQuery toDto(Query entity) {
        return new StoredQuery(
            entity.getUuid(), entity.getQuery(), entity.getResourceResultId(),
            entity.getStatus() == null ? null : entity.getStatus().name(), entity.getVersion(), encodeMetadata(entity.getMetadata())
        );
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
        return metadata == null ? null : Base64.getDecoder().decode(metadata);
    }

    private static String encodeMetadata(byte[] metadata) {
        return metadata == null ? null : Base64.getEncoder().encodeToString(metadata);
    }

    private static PicsureException notFound(UUID id) {
        return new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Query " + id + " not found");
    }
}
