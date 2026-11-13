package edu.harvard.hms.dbmi.avillach.operations.query;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Builds {@link Query} entities for tests that save them directly through the repository. */
public final class QueryFixtures {

    private QueryFixtures() {}

    /**
     * Returns an unsaved query with a random id already assigned. The entity no longer generates its id, so a bare {@code new Query()}
     * cannot be saved.
     */
    public static Query withRandomId() {
        Query query = new Query();
        query.setUuid(UUID.randomUUID());
        return query;
    }

    /**
     * Stores a new general query for a user, with the columns the first owned save of a query writes. The row is inserted with plain SQL,
     * so the call needs no transaction and joins the caller's when there is one.
     *
     * @param jdbc the connection source of the database under test
     * @param owner the UUID of the user the general query belongs to
     * @return the random id the general query was stored under
     */
    public static UUID storedGeneralQuery(JdbcTemplate jdbc, UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO query_to_variant (uuid, user_uuid, created_at) VALUES (?, ?, ?)", bytes(id), bytes(owner),
            Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS))
        );
        return id;
    }

    /**
     * Returns an unsaved v3 query row with a random id that is one variant of a general query. Its stored body names the same sample flag
     * and result type as its columns.
     *
     * @param owner the UUID of the user the row belongs to
     * @param generalQuery the id of the general query the row is filed under
     * @param sample whether the query is a sample query
     * @param resultType the result type name to store in the column and the body, or null to store none in either
     * @return the unsaved row
     */
    public static Query variant(UUID owner, UUID generalQuery, boolean sample, String resultType) {
        String expectedResultType = resultType == null ? "" : ",\"expectedResultType\":\"" + resultType + "\"";
        Query query = withRandomId()
            .setQuery("{\"query\":{\"select\":[],\"phenotypicClause\":null" + expectedResultType + ",\"sample\":" + sample + "}}")
            .setVersion("3");
        query.setUserUuid(owner);
        query.setQueryToVariantUuid(generalQuery);
        query.setSample(sample);
        query.setResultType(resultType);
        return query;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
