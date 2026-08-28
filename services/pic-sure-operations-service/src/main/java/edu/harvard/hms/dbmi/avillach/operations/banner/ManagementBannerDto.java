package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record ManagementBannerDto(
    UUID uuid, BannerStatus status, BannerLifecycle lifecycle, String htmlContent, String title, BannerAppearance appearance,
    BannerIcon icon, boolean dismissible, BannerAudience audience, BannerPlacement placement, JsonNode pageTargets, Instant startAt,
    Instant endAt, Integer priority, String presentationHash, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy,
    Instant publishedAt, String publishedBy
) {
    static ManagementBannerDto from(BannerOccurrence banner, Instant now) {
        return new ManagementBannerDto(
            banner.getUuid(), banner.getStatus(), lifecycle(banner, now), banner.getHtmlContent(), banner.getTitle(),
            banner.getAppearance(), banner.getIcon(), banner.isDismissible(), banner.getAudience(), banner.getPlacement(),
            banner.getPageTargets(), banner.getStartAt(), banner.getEndAt(), banner.getPriority(), banner.getPresentationHash(),
            banner.getCreatedAt(), banner.getCreatedBy(), banner.getUpdatedAt(), banner.getUpdatedBy(), banner.getPublishedAt(),
            banner.getPublishedBy()
        );
    }

    private static BannerLifecycle lifecycle(BannerOccurrence banner, Instant now) {
        return switch (banner.getStatus()) {
            case SAVED -> BannerLifecycle.SAVED;
            case DISABLED -> BannerLifecycle.DISABLED;
            case PUBLISHED -> {
                if (banner.getEndAt() != null && !banner.getEndAt().isAfter(now)) {
                    yield BannerLifecycle.EXPIRED;
                }
                if (banner.getStartAt() != null && banner.getStartAt().isAfter(now)) {
                    yield BannerLifecycle.SCHEDULED;
                }
                yield BannerLifecycle.ACTIVE;
            }
            case ARCHIVED -> throw new IllegalArgumentException("Archived banners are not part of the management read contract");
        };
    }
}
