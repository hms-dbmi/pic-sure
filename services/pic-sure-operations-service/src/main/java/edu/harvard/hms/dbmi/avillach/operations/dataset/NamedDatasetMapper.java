package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;

/**
 * Translates between the {@code pic-sure-api-data} {@link NamedDataset} entity and this service's DTOs. Pure field mapping -- the
 * {@code Query} referenced by {@code queryId} is resolved and persisted by {@link NamedDatasetService} through {@code QueryRepository},
 * then handed to this mapper already loaded.
 */
@Component
public class NamedDatasetMapper {

    // Historical query bodies can contain fields that are no longer part of the HPDS model.
    private static final ObjectMapper QUERY_MAPPER =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Logger log = LogManager.getLogger(NamedDatasetMapper.class);
    private static final List<String> LEGACY_FIELDS = List.of(
        "categoryFilters", "numericFilters", "fields", "crossCountFields", "requiredFields", "anyRecordOf", "anyRecordOfMulti",
        "variantInfoFilters", "expectedResultType"
    );

    public NamedDatasetDto toDto(NamedDataset e) {
        return new NamedDatasetDto(e.getUuid(), e.getUser(), e.getName(), toQueryDto(e.getQuery()), e.getArchived(), e.getMetadata());
    }

    /** {@code startTime} is converted to epoch millis here -- see {@link NamedDatasetQueryDto} for why the wire type is a number. */
    private NamedDatasetQueryDto toQueryDto(Query q) {
        if (q == null) {
            return null;
        }
        return new NamedDatasetQueryDto(
            q.getUuid(), convertQuery(q), q.getStartTime() == null ? null : q.getStartTime().getTime(), q.getStatus()
        );
    }

    /**
     * Normalize the saved request to a wrapper with an object-valued v3 {@code query} member. Legacy rows usually contain a request
     * wrapper, sometimes with a string-encoded inner query; other rows store the query body directly. Translate only the inner body so
     * request fields cannot silently deserialize into an empty legacy query. The stored row is not changed.
     */
    private static String convertQuery(Query q) {
        String stored = q.getQuery();
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        try {
            JsonNode root = QUERY_MAPPER.readTree(stored);
            if (!(root instanceof ObjectNode object)) {
                throw new IllegalArgumentException("Expected a saved request or query object");
            }
            object.remove("resourceCredentials");
            ObjectNode wrapper = object.has("query") ? object : QUERY_MAPPER.createObjectNode();
            JsonNode inner = object.has("query") ? object.get("query") : object;
            if (inner.isTextual()) {
                inner = QUERY_MAPPER.readTree(inner.textValue());
            }
            if (inner == null || !inner.isObject()) {
                throw new IllegalArgumentException("Expected an object-valued query");
            }
            boolean hasV3Fields =
                inner.has("phenotypicClause") || inner.has("select") || inner.has("genomicFilters") || inner.has("authorizationFilters");
            if (isV3(q) || hasV3Fields) {
                if (!hasV3Fields && !inner.has("expectedResultType")) {
                    throw new IllegalArgumentException("Unrecognized v3 query");
                }
                // Validate known field types, but preserve the original v3 body and any historical extra fields.
                QUERY_MAPPER.treeToValue(inner, edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class);
                // The frontend identifies v3 queries by this field, including an explicit null for an empty cohort filter.
                if (!inner.has("phenotypicClause")) {
                    ((ObjectNode) inner).putNull("phenotypicClause");
                }
            } else {
                if (LEGACY_FIELDS.stream().noneMatch(inner::has)) {
                    throw new IllegalArgumentException("Unrecognized legacy query");
                }
                edu.harvard.hms.dbmi.avillach.hpds.data.query.Query legacy =
                    QUERY_MAPPER.treeToValue(inner, edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.class);
                inner = QUERY_MAPPER.valueToTree(QueryTranslator.translate(legacy));
            }
            wrapper.set("query", inner);
            return QUERY_MAPPER.writeValueAsString(wrapper);
        } catch (JsonProcessingException | UntranslatableQueryException | IllegalArgumentException e) {
            log.warn("Unable to convert saved query {} to v3", q.getUuid());
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unable to convert saved query to v3");
        }
    }

    /** Returns whether the stored query's major version is 3. */
    static boolean isV3(Query query) {
        String v = query.getVersion();
        return v != null && v.split("\\.")[0].equals("3");
    }

    /** {@code user} is the caller's EMAIL (owner key); {@code query} is pre-resolved by the service. */
    public NamedDataset toEntity(String user, Query query, NamedDatasetRequestDto req) {
        return new NamedDataset().setUser(user).setQuery(query).setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
    }
}
