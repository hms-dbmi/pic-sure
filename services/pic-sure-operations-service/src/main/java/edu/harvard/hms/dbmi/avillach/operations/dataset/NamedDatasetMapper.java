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
import com.fasterxml.jackson.databind.node.NullNode;

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

    /** Returns a v3 request wrapper without changing the stored query. */
    private static String convertQuery(Query q) {
        String stored = q.getQuery();
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        try {
            ObjectNode request = requireObject(QUERY_MAPPER.readTree(stored));
            request.remove("resourceCredentials");
            JsonNode body = request.has("query") ? request.get("query") : request;
            // Some historical requests JSON-encode their inner query a second time.
            ObjectNode query = requireObject(body.isTextual() ? QUERY_MAPPER.readTree(body.textValue()) : body);
            ObjectNode wrapper = request.has("query") ? request : QUERY_MAPPER.createObjectNode();
            wrapper.set("query", toV3(query, isV3(q)));
            return QUERY_MAPPER.writeValueAsString(wrapper);
        } catch (JsonProcessingException | UntranslatableQueryException | IllegalArgumentException e) {
            log.warn("Unable to convert saved query {} to v3", q.getUuid());
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unable to convert saved query to v3");
        }
    }

    private static ObjectNode toV3(ObjectNode query, boolean v3) throws JsonProcessingException, UntranslatableQueryException {
        if (!v3) {
            // Reject unrelated objects before lenient V2 deserialization can silently turn them into empty queries.
            if (LEGACY_FIELDS.stream().noneMatch(query::has)) {
                throw new IllegalArgumentException("Unrecognized saved V2 query");
            }
            var legacy = QUERY_MAPPER.treeToValue(query, edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.class);
            return QUERY_MAPPER.valueToTree(QueryTranslator.translate(legacy));
        }
        // Validate types without dropping historical extra fields. The frontend needs the v3 discriminator even when null.
        QUERY_MAPPER.treeToValue(query, edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class);
        query.putIfAbsent("phenotypicClause", NullNode.getInstance());
        return query;
    }

    private static ObjectNode requireObject(JsonNode node) {
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException("Expected a query object");
    }

    /** The stored version determines the format: 3.x is V3; all other values, including null, are V2. */
    static boolean isV3(Query query) {
        String v = query.getVersion();
        return v != null && (v.equals("3") || v.startsWith("3."));
    }

    /** {@code user} is the caller's EMAIL (owner key); {@code query} is pre-resolved by the service. */
    public NamedDataset toEntity(String user, Query query, NamedDatasetRequestDto req) {
        return new NamedDataset().setUser(user).setQuery(query).setName(req.name()).setArchived(req.archived()).setMetadata(req.metadata());
    }
}
