package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.UUID;

public record ArchivedBannerDto(UUID uuid, BannerStatus status, Instant archivedAt, String archivedBy) {

    static ArchivedBannerDto from(BannerOccurrence banner) {
        return new ArchivedBannerDto(banner.getUuid(), banner.getStatus(), banner.getArchivedAt(), banner.getArchivedBy());
    }
}
