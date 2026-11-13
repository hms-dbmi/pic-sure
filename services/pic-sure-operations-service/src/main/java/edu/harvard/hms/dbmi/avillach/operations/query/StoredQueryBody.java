package edu.harvard.hms.dbmi.avillach.operations.query;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.QueryTranslator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.translation.UntranslatableQueryException;

/**
 * Reads the request body stored on a {@link Query} row. A row holds the request as it was submitted: the query alone or inside a wrapper
 * object, in the format its {@code version} column names. Everything here works on a parsed copy, so the row and its stored bytes are never
 * changed.
 */
public final class StoredQueryBody {

    private static final ObjectMapper MAPPER = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final String V3_VERSION = "3";
    private static final List<String> LEGACY_FIELDS = List.of(
        "categoryFilters", "numericFilters", "fields", "crossCountFields", "requiredFields", "anyRecordOf", "anyRecordOfMulti",
        "variantInfoFilters", "expectedResultType"
    );

    private StoredQueryBody() {}

    /**
     * Parses a stored request body into a wrapper object whose {@code query} member is the query as a v3 object. The stored
     * {@code resourceCredentials} are dropped and the other members beside the query are kept. A query that was stored as JSON text inside
     * the wrapper, as some old requests were, is parsed a second time.
     *
     * @param row the row the body belongs to, whose version decides the stored format
     * @param stored the body of the row, not blank
     * @return the wrapper
     * @throws JsonProcessingException when the body is not JSON, or its query does not have the member types of its format
     * @throws UntranslatableQueryException when the query was stored before v3 and has no v3 equivalent
     * @throws IllegalArgumentException when the body or its query is not a JSON object, or is not a query this service recognizes
     */
    public static ObjectNode v3Request(Query row, String stored) throws JsonProcessingException, UntranslatableQueryException {
        ObjectNode request = requireObject(MAPPER.readTree(stored));
        request.remove("resourceCredentials");
        JsonNode body = request.has("query") ? request.get("query") : request;
        ObjectNode query = requireObject(body.isTextual() ? MAPPER.readTree(body.textValue()) : body);
        ObjectNode wrapper = request.has("query") ? request : MAPPER.createObjectNode();
        wrapper.set("query", isV3(row) ? validatedV3(query) : translatedToV3(query));
        return wrapper;
    }

    /**
     * Reads the result type a stored query asks for, from its body in the v3 form {@link #v3Request} gives it. A query stored before v3 is
     * translated first, so it has the result type the translation gives it. Only the exact name of a {@link ResultType} constant counts as
     * a result type, which is the rule a save applies to the result type it stores.
     *
     * @param row the stored query
     * @return the result type, or empty when the row has no body, or the body names no result type or one that is not a constant's name
     * @throws UncheckedIOException when the stored bytes are not gzip
     * @throws IllegalArgumentException when the body cannot be read as a query, for any of the reasons {@link #v3Request} refuses one
     */
    public static Optional<ResultType> resultTypeOf(Query row) {
        String stored = row.getQuery();
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        JsonNode named;
        try {
            named = v3Request(row, stored).get("query").get("expectedResultType");
        } catch (JsonProcessingException | UntranslatableQueryException e) {
            throw new IllegalArgumentException("The stored body cannot be read as a query", e);
        }
        if (named == null || !named.isTextual()) {
            return Optional.empty();
        }
        return Stream.of(ResultType.values()).filter(type -> type.name().equals(named.textValue())).findFirst();
    }

    /**
     * Tells which format a row's body is stored in.
     *
     * @param row the stored query
     * @return true when the version is {@code "3"}; false for every other value, null included, which all mean the format before v3
     */
    private static boolean isV3(Query row) {
        return V3_VERSION.equals(row.getVersion());
    }

    /**
     * Checks the member types of a v3 query and returns it with every member it was stored with, including members v3 does not define. The
     * {@code phenotypicClause} member is added as null when it is absent, because readers tell a v3 query by its presence.
     */
    private static ObjectNode validatedV3(ObjectNode query) throws JsonProcessingException {
        MAPPER.treeToValue(query, edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query.class);
        query.putIfAbsent("phenotypicClause", NullNode.getInstance());
        return query;
    }

    /**
     * Translates a query stored before v3. An object with none of the members of that format is refused first, because the lenient parse
     * would otherwise read it as an empty query.
     */
    private static ObjectNode translatedToV3(ObjectNode query) throws JsonProcessingException, UntranslatableQueryException {
        if (LEGACY_FIELDS.stream().noneMatch(query::has)) {
            throw new IllegalArgumentException("Unrecognized saved V2 query");
        }
        var legacy = MAPPER.treeToValue(query, edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.class);
        return MAPPER.valueToTree(QueryTranslator.translate(legacy));
    }

    private static ObjectNode requireObject(JsonNode node) {
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException("Expected a query object");
    }
}
