package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

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
}
