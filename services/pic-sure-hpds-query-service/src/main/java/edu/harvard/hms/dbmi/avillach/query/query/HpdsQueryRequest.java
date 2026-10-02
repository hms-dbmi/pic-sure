package edu.harvard.hms.dbmi.avillach.query.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.StrictQueryDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body of the query endpoints under {@code /hpds/{backend}/query} and {@code /hpds/open/query}: a v3 query under the {@code query}
 * key.
 *
 * <p>The envelope ignores members it does not know, so a body that still carries {@code resourceUUID}, {@code resourceCredentials} or
 * {@code @type} binds as it did when the endpoints bound {@code QueryRequest}, and those members are dropped. The query itself is read
 * through {@link StrictQueryDeserializer}, so a member outside the v3 {@link Query} or any of its clauses and filters fails to bind and the
 * request answers 400 instead of running as a query with fewer filters than the caller wrote.
 *
 * @param query the v3 query, or null when the body carries none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    description = "A v3 query under the query key. Members other than query are ignored. A query carrying a member the v3 query, "
        + "its clauses or its filters do not define, such as the v1 fields or numericFilters, is answered with 400."
)
public record HpdsQueryRequest(
    @JsonDeserialize(using = StrictQueryDeserializer.class) @Schema(
        description = "The v3 query. Submit and sync need it. Status, result and signed-url read the stored query and accept an empty object."
    ) Query query
) {

    /**
     * Wraps the query in the envelope HPDS and the operations service receive. The envelope carries no resource UUID, because the backend
     * is chosen by the request path.
     *
     * @return a new envelope holding this request's query
     */
    public GeneralQueryRequest toOutbound() {
        return new GeneralQueryRequest().setQuery(query);
    }
}
