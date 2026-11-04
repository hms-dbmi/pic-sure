package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The result of archiving a banner.")
public record ArchivedBannerDto(
    @Schema(
        description = "The id of the archived banner.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The stored state of the banner, which is ARCHIVED on this response.", requiredMode = Schema.RequiredMode.REQUIRED
    ) BannerStatus status,
    @Schema(
        description = "When the banner was archived.", example = "2026-09-30T14:05:00Z", requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant archivedAt,
    @Schema(
        description = "The gateway user id of the administrator who archived the banner.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String archivedBy
) {

    static ArchivedBannerDto from(BannerOccurrence banner) {
        return new ArchivedBannerDto(banner.getUuid(), banner.getStatus(), banner.getArchivedAt(), banner.getArchivedBy());
    }
}
