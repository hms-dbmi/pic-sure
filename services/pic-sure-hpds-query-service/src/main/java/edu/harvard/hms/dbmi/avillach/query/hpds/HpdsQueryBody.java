package edu.harvard.hms.dbmi.avillach.query.hpds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

/**
 * Builds the query HPDS receives. HPDS stores a result under the {@code picsureId} of the query it is given, so that member decides which
 * stored result a submit replaces. A caller of this service never chooses it: whatever {@code picsureId} the inbound query carries is
 * replaced by the id this service assigned, or removed when the service assigned none. Both HPDS clients, {@link ResourceWebClient} and
 * {@code AggregateBackendClient}, send every body through {@link #of}, so no route to HPDS can forward a client's value.
 */
public final class HpdsQueryBody {

    private static final String RESULT_ID_MEMBER = "picsureId";

    private HpdsQueryBody() {}

    /**
     * Returns the query to send to HPDS in place of the one a route holds. The query passed in is not changed.
     *
     * <p>A typed v3 {@link Query} is copied with its {@code picsureId} replaced. A query read from JSON as a map, which is what the upgrade
     * of a stored query produces when consent scoping does not type it, is copied with its {@code picsureId} member replaced. A null query
     * and a body of any other type, such as a search term, are returned as they are, because they have no such member.
     *
     * @param query the {@code query} member of the envelope a route is about to send
     * @param serverResultId the id this service assigned to the result, or null when it assigned none and HPDS derives its own key
     * @return the query HPDS receives
     */
    public static Object of(Object query, UUID serverResultId) {
        if (query instanceof Query typed) {
            return typed.setPicsureId(serverResultId);
        }
        if (query instanceof Map<?, ?> untyped) {
            Map<Object, Object> copy = new LinkedHashMap<>(untyped);
            copy.put(RESULT_ID_MEMBER, serverResultId);
            return copy;
        }
        return query;
    }
}
