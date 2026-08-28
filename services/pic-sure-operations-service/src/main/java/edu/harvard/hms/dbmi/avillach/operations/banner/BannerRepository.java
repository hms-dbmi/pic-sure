package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface BannerRepository extends JpaRepository<BannerOccurrence, UUID> {

    @Query("""
        SELECT banner
        FROM banner_occurrence banner
        WHERE banner.status = edu.harvard.hms.dbmi.avillach.operations.banner.BannerStatus.PUBLISHED
          AND banner.startAt <= :now
          AND (banner.endAt IS NULL OR banner.endAt > :now)
        ORDER BY banner.priority ASC, banner.uuid ASC
        """)
    List<BannerOccurrence> findActive(@Param("now") Instant now);

    @Query("""
        SELECT COALESCE(MAX(banner.priority), 0)
        FROM banner_occurrence banner
        WHERE banner.status = edu.harvard.hms.dbmi.avillach.operations.banner.BannerStatus.PUBLISHED
          AND (banner.endAt IS NULL OR banner.endAt > :now)
        """)
    int findMaximumOrderablePriority(@Param("now") Instant now);

    @Query("""
        SELECT banner
        FROM banner_occurrence banner
        WHERE banner.status <> edu.harvard.hms.dbmi.avillach.operations.banner.BannerStatus.ARCHIVED
        """)
    List<BannerOccurrence> findAllManaged();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT banner FROM banner_occurrence banner WHERE banner.uuid = :uuid")
    Optional<BannerOccurrence> findByIdForUpdate(@Param("uuid") UUID uuid);
}
