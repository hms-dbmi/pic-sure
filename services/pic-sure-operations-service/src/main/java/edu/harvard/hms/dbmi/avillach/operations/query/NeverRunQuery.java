package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

/**
 * What {@link QueryPersistenceService#storeNeverRun} stores: a query row that belongs to a user and has not been sent to HPDS. The id and
 * the general query are the ones the query service derives for the same user and the same query, so a later submit, status or result
 * request for that query finds this row and runs it.
 *
 * @param picsureId the id to store the row under, which is also the id HPDS will store the result under
 * @param userUuid the UUID of the user the row belongs to
 * @param queryToVariantUuid the id of the general query the row is a variant of
 * @param sample whether the query returns a sample of the matching patients
 * @param resultType the result type the query asks for, or null when it names none
 * @param query the request body to store, as JSON: an envelope whose {@code query} member is a v3 query that carries {@code picsureId} and
 *        no authorization filters
 * @param startTime the time to store as the start time of the row, or null to store none
 */
public record NeverRunQuery(
    UUID picsureId, UUID userUuid, UUID queryToVariantUuid, boolean sample, ResultType resultType, String query, Instant startTime
) {

    /**
     * Checks that the row has an id, an owner, a general query and a body.
     *
     * @throws NullPointerException when one of the four is null
     */
    public NeverRunQuery {
        Objects.requireNonNull(picsureId, "picsureId is null");
        Objects.requireNonNull(userUuid, "userUuid is null");
        Objects.requireNonNull(queryToVariantUuid, "queryToVariantUuid is null");
        Objects.requireNonNull(query, "query is null");
    }
}
