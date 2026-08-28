package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BannerVersionRepository extends JpaRepository<BannerVersion, UUID> {

    List<BannerVersion> findByBannerUuidOrderByVersionNumber(UUID bannerUuid);

    boolean existsByBannerUuidAndVersionNumber(UUID bannerUuid, int versionNumber);

    @Query("SELECT COALESCE(MAX(version.versionNumber), 0) FROM banner_version version WHERE version.bannerUuid = :bannerUuid")
    int findMaximumVersionNumber(@Param("bannerUuid") UUID bannerUuid);
}
