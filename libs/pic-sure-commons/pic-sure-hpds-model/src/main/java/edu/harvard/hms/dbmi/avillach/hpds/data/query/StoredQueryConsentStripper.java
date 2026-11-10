package edu.harvard.hms.dbmi.avillach.hpds.data.query;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;

/**
 * Removes consent values from a stored query request body, so that the body can be shown to a caller other than the one the query ran for.
 *
 * <p>The query is the {@code query} member of the body. A body that is a JSON object with no such member is read as the query itself. When
 * the member is JSON text that holds a JSON object, that object is the query: the member stays text, and the text is rewritten only when a
 * rule below changed the object.
 *
 * <p>The consent concept paths are the ones in {@link AuthorizationFilter#CONSENT_CONCEPT_PATHS}. The rules are the same whatever format
 * the query was stored in: <ul> <li>{@code authorizationFilters}, when the query has the member, becomes an empty list.</li> <li>Every
 * filter in {@code phenotypicClause} whose {@code conceptPath} is a consent concept path is removed, at any depth. A group that loses its
 * last clause this way is removed as well, and so is the group above it when that was its last clause. When nothing is left,
 * {@code phenotypicClause} becomes null. A group that was stored with no clauses is kept.</li> <li>Every entry of {@code categoryFilters}
 * and {@code numericFilters}, the filter maps of a query stored before v3, whose key is a consent concept path is removed.</li> </ul>
 * Nothing else changes. {@code select}, and the field lists of a query stored before v3, keep a consent concept path they name, because a
 * path names a column and carries no value. A filter on any other path stays, {@code \_parent_consents\} included.
 *
 * <p>A body that is not a JSON object, and a body whose {@code query} member is neither an object nor text that holds one, has no query
 * these rules can read and is left as it is.
 */
public final class StoredQueryConsentStripper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUERY = "query";
    private static final String AUTHORIZATION_FILTERS = "authorizationFilters";
    private static final String PHENOTYPIC_CLAUSE = "phenotypicClause";
    private static final String PHENOTYPIC_CLAUSES = "phenotypicClauses";
    private static final String CONCEPT_PATH = "conceptPath";
    private static final List<String> PRE_V3_FILTER_MAPS = List.of("categoryFilters", "numericFilters");

    private StoredQueryConsentStripper() {}

    /**
     * Removes the consent values from a stored query request body.
     *
     * @param body the parsed body; changed in place
     */
    public static void strip(JsonNode body) {
        if (!(body instanceof ObjectNode envelope)) {
            return;
        }
        JsonNode query = envelope.get(QUERY);
        if (query == null) {
            stripQuery(envelope);
        } else if (query instanceof ObjectNode queryObject) {
            stripQuery(queryObject);
        } else if (query.isTextual()) {
            stripQueryText(envelope, query.asText());
        }
    }

    private static void stripQueryText(ObjectNode envelope, String queryText) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(queryText);
        } catch (JsonProcessingException notJson) {
            return;
        }
        if (!(parsed instanceof ObjectNode queryObject)) {
            return;
        }
        JsonNode asStored = queryObject.deepCopy();
        stripQuery(queryObject);
        if (!queryObject.equals(asStored)) {
            envelope.put(QUERY, queryObject.toString());
        }
    }

    private static void stripQuery(ObjectNode query) {
        if (query.has(AUTHORIZATION_FILTERS)) {
            query.putArray(AUTHORIZATION_FILTERS);
        }
        for (String filterMap : PRE_V3_FILTER_MAPS) {
            if (query.get(filterMap) instanceof ObjectNode filters) {
                filters.remove(AuthorizationFilter.CONSENT_CONCEPT_PATHS);
            }
        }
        if (pruneConsentFilters(query.get(PHENOTYPIC_CLAUSE))) {
            query.putNull(PHENOTYPIC_CLAUSE);
        }
    }

    /**
     * Removes every consent filter below a clause and every group that is left without a clause.
     *
     * @param clause a node of the clause tree; a group is changed in place
     * @return whether the clause itself has to go: it is a consent filter, or a group whose last clause was removed
     */
    private static boolean pruneConsentFilters(JsonNode clause) {
        if (!(clause instanceof ObjectNode node)) {
            return false;
        }
        if (isConsentFilter(node)) {
            return true;
        }
        if (!(node.get(PHENOTYPIC_CLAUSES) instanceof ArrayNode clauses) || clauses.isEmpty()) {
            return false;
        }
        for (int index = clauses.size() - 1; index >= 0; index--) {
            if (pruneConsentFilters(clauses.get(index))) {
                clauses.remove(index);
            }
        }
        return clauses.isEmpty();
    }

    private static boolean isConsentFilter(ObjectNode clause) {
        JsonNode conceptPath = clause.get(CONCEPT_PATH);
        return conceptPath != null && conceptPath.isTextual() && AuthorizationFilter.CONSENT_CONCEPT_PATHS.contains(conceptPath.asText());
    }
}
