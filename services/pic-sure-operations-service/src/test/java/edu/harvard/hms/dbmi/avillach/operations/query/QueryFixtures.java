package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.UUID;

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
}
