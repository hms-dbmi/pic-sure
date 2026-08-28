package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative result of archiving an occurrence. Archived rows have no management representation, so this carries only what the caller
 * needs to reconcile its list: which occurrence left normal management, when, and by whom.
 */
public record ArchivedBannerDto(UUID uuid, BannerStatus status, Instant archivedAt, String archivedBy) {

    static ArchivedBannerDto from(BannerOccurrence banner) {
        return new ArchivedBannerDto(banner.getUuid(), banner.getStatus(), banner.getArchivedAt(), banner.getArchivedBy());
    }
}
