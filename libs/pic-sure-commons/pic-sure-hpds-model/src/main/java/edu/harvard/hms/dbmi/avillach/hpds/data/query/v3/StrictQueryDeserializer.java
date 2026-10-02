package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The ingress contract for a v3 {@link Query}: reads the query and fails on any member that the query, its clauses or its filters do not
 * define. A v1-shaped body ({@code fields}, {@code numericFilters}, {@code categoryFilters}) and a stray member anywhere in the tree fail
 * to bind instead of binding as a query with fewer filters than the caller wrote.
 *
 * <p>Attach it with {@code @JsonDeserialize(using = StrictQueryDeserializer.class)} to the {@link Query} member of a request record. The
 * {@link Query} record itself stays as lenient as the mapper that reads it, because the operations service re-parses persisted
 * saved-dataset queries with unknown members on purpose. Spring Boot's request mapper has {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so
 * neither {@code @JsonIgnoreProperties(ignoreUnknown = false)} on the record nor the mapper's default would refuse the body; this
 * deserializer reads the node with its own mapper, which has the feature on.
 */
public final class StrictQueryDeserializer extends JsonDeserializer<Query> {

    private static final ObjectMapper STRICT = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /**
     * Reads the query at the parser's current token with a mapper that fails on unknown members.
     *
     * @param parser the body parser, positioned on the query value
     * @param context the binding context of the enclosing record
     * @return the query
     * @throws IOException when the value is not a v3 query, including when it or a nested clause or filter carries an unknown member
     */
    @Override
    public Query deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return STRICT.readerFor(Query.class).readValue(parser);
    }
}
