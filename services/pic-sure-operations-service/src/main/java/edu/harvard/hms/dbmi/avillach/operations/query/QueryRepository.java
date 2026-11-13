package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for queries; {@code findById} and {@code save} are inherited from {@link JpaRepository}. */
public interface QueryRepository extends JpaRepository<Query, UUID> {

    /**
     * Finds a query row and locks it for the rest of the caller's transaction, as {@code SELECT ... FOR UPDATE} does. A second transaction
     * that asks for the same row waits here until the first one ends, and then reads the row as the first one left it. Call it inside a
     * transaction, before reading anything else that belongs to the row.
     *
     * @param uuid the id of the query row
     * @return the locked row, or empty when no row has the id
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Query> findLockedByUuid(UUID uuid);

    /**
     * Lists the variants one user stored under the given general queries, without their query bodies. The rows of all the general queries
     * come back in one list, ordered by sample flag with full results first, then by result type, then by row id, so the order is the same
     * on every call. MySQL puts a row with no result type before the typed rows of the same sample flag. A row that belongs to another
     * user, or to none, is left out even when it is filed under one of the general queries.
     *
     * @param generalQueries the ids of the general queries; must not be empty
     * @param owner the UUID of the user whose rows are listed
     * @return the variant columns of the matching rows, or an empty list when there are none
     */
    @org.springframework.data.jpa.repository.Query(
        "select new edu.harvard.hms.dbmi.avillach.operations.query.QueryVariantRow(q.queryToVariantUuid, q.uuid, q.sample, q.resultType)"
            + " from query q where q.queryToVariantUuid in :generalQueries and q.userUuid = :owner"
            + " order by q.sample, q.resultType, q.uuid"
    )
    List<QueryVariantRow> findVariantsOf(@Param("generalQueries") Collection<UUID> generalQueries, @Param("owner") UUID owner);
}
