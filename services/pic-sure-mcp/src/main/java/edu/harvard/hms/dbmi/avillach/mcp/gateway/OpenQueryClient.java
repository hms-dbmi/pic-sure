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
 * Runs obfuscated open-access queries through the gateway. It has one path: the open channel's synchronous query. There is no backend
 * setting, no async submission, and no status, result, or metadata call, so nothing here can reach authorized data. The authorized count
 * has its own client, {@link AuthorizedQueryClient}. Failures surface as {@link org.springframework.web.client.RestClientException}s for
 * the calling tool to map.
 */
@Component
public class OpenQueryClient {

    /** Gateway path of the open channel's synchronous query, the only query path this service calls. */
    public static final String QUERY_SYNC_PATH = "/hpds/open/query/sync";

    /**
     * The result types this service sends: the four the open channel obfuscates. The open channel passes other types through without
     * obfuscation, so this list is kept here rather than trusted to the query service.
     */
    public static final Set<ResultType> ALLOWED_RESULT_TYPES = Collections.unmodifiableSet(
        EnumSet.of(ResultType.COUNT, ResultType.CROSS_COUNT, ResultType.CATEGORICAL_CROSS_COUNT, ResultType.CONTINUOUS_CROSS_COUNT)
    );

    private final RestClient restClient;

    /**
     * Creates the client.
     *
     * @param restClient the gateway client
     */
    public OpenQueryClient(@Qualifier(GatewayClientConfig.GATEWAY_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Posts a query to the open channel in the {@code {"query": ...}} envelope, replaying the caller's headers.
     *
     * @param query the query, with a result type from {@link #ALLOWED_RESULT_TYPES}, no authorization filters, and no ids
     * @param caller the caller's headers to replay
     * @return the open channel's response body, possibly null
     * @throws IllegalArgumentException if the query's result type is not allowed or it carries authorization filters or an id, before
     *         anything is sent
     */
    public String querySync(Query query, CallerHeaders caller) {
        SyncQueries.QueryRequest request = SyncQueries.checked(query, ALLOWED_RESULT_TYPES);
        return restClient.post().uri(QUERY_SYNC_PATH).headers(caller::applyTo).contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON).body(request).retrieve().body(String.class);
    }
}
