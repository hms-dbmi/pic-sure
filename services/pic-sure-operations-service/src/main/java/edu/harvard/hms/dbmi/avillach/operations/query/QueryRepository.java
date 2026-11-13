package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Reads the first rows that have no result type, in the order the database sorts their ids. The rows are read in a short read-only
     * transaction of their own and come back detached. They are for reading only: saving one would write every column back with the values
     * it was loaded with.
     *
     * @param limit the largest number of rows to return
     * @return the rows, with their bodies, or an empty list when every row has a result type
     */
    @Transactional(readOnly = true)
    @org.springframework.data.jpa.repository.Query("select q from query q where q.resultType is null order by q.uuid")
    List<Query> findFirstWithoutResultType(Limit limit);

    /**
     * Reads the next rows that have no result type and whose id the database sorts after the given one, in that same order. Passing the
     * last id of the page before makes each call move on, whether or not the rows of that page were given a result type since. The rows are
     * read and returned as {@link #findFirstWithoutResultType} does.
     *
     * @param after the id every returned row comes after
     * @param limit the largest number of rows to return
     * @return the rows, with their bodies, or an empty list when no later row lacks a result type
     */
    @Transactional(readOnly = true)
    @org.springframework.data.jpa.repository.Query("select q from query q where q.resultType is null and q.uuid > :after order by q.uuid")
    List<Query> findWithoutResultTypeAfter(@Param("after") UUID after, Limit limit);

    /**
     * Stores a result type on a row that has none, with one statement that names only that column. A row that already has a result type
     * when the statement runs is left as it is, so the write never replaces a value another writer stored, and no other column of the row
     * is read or written. Called outside a transaction, the statement runs in one of its own that ends with the call. It waits for a
     * transaction that holds the row lock, and then sees the row as that transaction left it.
     *
     * @param uuid the id of the row
     * @param resultType the result type name to store
     * @return 1 when the row was given the result type; 0 when it already had one, or no row has the id
     */
    @Modifying
    @Transactional
    @org.springframework.data.jpa.repository.Query(
        "update query q set q.resultType = :resultType where q.uuid = :uuid and q.resultType is null"
    )
    int setResultTypeWhereAbsent(@Param("uuid") UUID uuid, @Param("resultType") String resultType);
}
