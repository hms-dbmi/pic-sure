package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record BannerDto(
    UUID uuid, BannerStatus status, String htmlContent, String title, BannerAppearance appearance, BannerIcon icon, boolean dismissible,
    BannerAudience audience, BannerPlacement placement, JsonNode pageTargets, Instant startAt, Instant endAt, Integer priority,
    String presentationHash, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, Instant publishedAt,
    String publishedBy
) {
    static BannerDto from(BannerOccurrence banner) {
        return new BannerDto(
            banner.getUuid(), banner.getStatus(), banner.getHtmlContent(), banner.getTitle(), banner.getAppearance(), banner.getIcon(),
            banner.isDismissible(), banner.getAudience(), banner.getPlacement(), banner.getPageTargets(), banner.getStartAt(),
            banner.getEndAt(), banner.getPriority(), banner.getPresentationHash(), banner.getCreatedAt(), banner.getCreatedBy(),
            banner.getUpdatedAt(), banner.getUpdatedBy(), banner.getPublishedAt(), banner.getPublishedBy()
        );
    }
}
