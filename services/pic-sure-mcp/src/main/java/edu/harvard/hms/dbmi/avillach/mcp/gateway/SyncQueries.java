package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

import java.util.Set;

/**
 * The guards and request envelope both query clients share. It starts no request itself, so each client still chooses its own path.
 */
final class SyncQueries {

    private SyncQueries() {}

    /**
     * Checks a query before a client sends it.
     *
     * @param query the query
     * @param allowed the result types the calling client may send
     * @return the request envelope to send
     * @throws IllegalArgumentException if the query's result type is not allowed or it carries authorization filters or an id
     */
    static QueryRequest checked(Query query, Set<ResultType> allowed) {
        if (query == null || !allowed.contains(query.expectedResultType())) {
            throw new IllegalArgumentException("This client does not send that result type");
        }
        if (!query.authorizationFilters().isEmpty() || query.picsureId() != null || query.id() != null) {
            throw new IllegalArgumentException("Queries carry no authorization filters and no ids");
        }
        return new QueryRequest(query);
    }

    /**
     * The {@code {"query": ...}} envelope both channels take. It carries no {@code resourceUUID}; the query service picks the backend.
     *
     * @param query the query
     */
    record QueryRequest(Query query) {
    }
}
