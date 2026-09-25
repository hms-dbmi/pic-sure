package edu.harvard.hms.dbmi.avillach.auth.repository;

import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    Page<ApiKey> findByKeyType(ApiKeyType keyType, Pageable pageable);

    // dedicated single-column update: a save() of the entity loaded during verification would merge
    // every column, letting a stale revoked_at=null overwrite a concurrent revocation. The cutoff
    // makes the write throttle atomic — concurrent requests holding the same stale snapshot must
    // not each issue an update
    @Modifying
    @Transactional
    @Query(
        "UPDATE api_key k SET k.lastUsedAt = :now WHERE k.uuid = :uuid AND k.revokedAt IS NULL"
            + " AND (k.expiresAt IS NULL OR k.expiresAt > :now)" + " AND (k.lastUsedAt IS NULL OR k.lastUsedAt < :cutoff)"
    )
    int touchLastUsed(@Param("uuid") UUID uuid, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
