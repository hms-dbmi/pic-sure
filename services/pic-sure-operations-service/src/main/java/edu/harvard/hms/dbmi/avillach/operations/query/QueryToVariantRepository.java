package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for general queries. Rows are inserted once and read, never updated or deleted. */
public interface QueryToVariantRepository extends JpaRepository<QueryToVariant, UUID> {

    /**
     * Inserts a general query unless a row with the id is already stored. A stored row is left exactly as it is, whatever owner it has, and
     * no duplicate-key failure is raised, so the caller's transaction stays usable. When another transaction is inserting the same id, this
     * call waits for that transaction to end. Call it inside a transaction and load the row afterward to learn its owner.
     *
     * @param uuid the id of the general query, as its 16 bytes with the most significant byte first
     * @param userUuid the owner of the general query, as its 16 bytes with the most significant byte first
     * @param createdAt the creation time to store when the row is inserted
     * @return 1 when the row was inserted, 0 when a row with the id was already stored
     */
    @Modifying
    @org.springframework.data.jpa.repository.Query(
        value = "INSERT IGNORE INTO query_to_variant (uuid, user_uuid, created_at) VALUES (:uuid, :userUuid, :createdAt)",
        nativeQuery = true
    )
    int insertIfAbsent(@Param("uuid") byte[] uuid, @Param("userUuid") byte[] userUuid, @Param("createdAt") Instant createdAt);
}
