package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Runs an authorized participant count through the gateway, as the caller. It has one path, the authorized channel's synchronous query, and
 * count result types only, so it returns numbers and never row-level data. It replays the caller's own {@code Authorization} header; the
 * gateway introspects the token and the query service applies the caller's consents, so this service holds no credentials of its own and
 * adds no access. Failures surface as {@link org.springframework.web.client.RestClientException}s for the calling tool to map.
 */
@Component
public class AuthorizedQueryClient {

    /** Gateway path of the authorized channel's synchronous query, the only authorized path this service calls. */
    public static final String AUTHORIZED_QUERY_SYNC_PATH = "/hpds/auth/query/sync";

    /** The result types this service sends to the authorized channel: counts only, so it can return numbers and never rows. */
    public static final Set<ResultType> ALLOWED_RESULT_TYPES = Collections.unmodifiableSet(
        EnumSet.of(
            ResultType.COUNT, ResultType.CROSS_COUNT, ResultType.CATEGORICAL_CROSS_COUNT, ResultType.CONTINUOUS_CROSS_COUNT,
            ResultType.OBSERVATION_CROSS_COUNT
        )
    );

    private final RestClient restClient;
    private final RestClient crossCountClient;

    /**
     * Creates the client.
     *
     * @param restClient the gateway client, for single counts
     * @param crossCountClient the gateway client for cross counts, which waits longer for an answer
     */
    public AuthorizedQueryClient(
        @Qualifier(GatewayClientConfig.GATEWAY_REST_CLIENT) RestClient restClient,
        @Qualifier(GatewayClientConfig.CROSS_COUNT_REST_CLIENT) RestClient crossCountClient
    ) {
        this.restClient = restClient;
        this.crossCountClient = crossCountClient;
    }

    /**
     * Posts a count query to the authorized channel in the {@code {"query": ...}} envelope, replaying the caller's headers. A cross count
     * goes through the client that waits longer for an answer.
     *
     * @param query the query, with a result type from {@link #ALLOWED_RESULT_TYPES}, no authorization filters, and no ids
     * @param caller the caller's headers to replay; its {@code Authorization} header is required
     * @return the authorized channel's response body, possibly null
     * @throws IllegalArgumentException if the query's result type is not allowed, it carries authorization filters or an id, or the caller
     *         has no {@code Authorization} header, before anything is sent
     */
    public String countSync(Query query, CallerHeaders caller) {
        SyncQueries.QueryRequest request = SyncQueries.checked(query, ALLOWED_RESULT_TYPES);
        if (caller == null || caller.authorization() == null || caller.authorization().isBlank()) {
            throw new IllegalArgumentException("An authorized count needs the caller's Authorization header");
        }
        RestClient client = query.expectedResultType() == ResultType.COUNT ? restClient : crossCountClient;
        return client.post().uri(AUTHORIZED_QUERY_SYNC_PATH).headers(caller::applyTo).contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON).body(request).retrieve().body(String.class);
    }
}
