package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record ManagementBannerDto(
    UUID uuid, BannerStatus status, BannerLifecycle lifecycle, String htmlContent, String title, BannerAppearance appearance,
    BannerIcon icon, boolean dismissible, BannerAudience audience, BannerPlacement placement, List<BannerPageTarget> pageTargets,
    Instant startAt, Instant endAt, Integer priority, String presentationHash, Instant createdAt, String createdBy, Instant updatedAt,
    String updatedBy, Instant publishedAt, String publishedBy, Instant disabledAt, String disabledBy
) {
    static Optional<ManagementBannerDto> from(BannerOccurrence banner, Instant now) {
        List<BannerPageTarget> pageTargets = banner.getPageTargets();
        if (pageTargets == null) {
            return Optional.empty();
        }
        return lifecycle(banner, now).map(
            lifecycle -> new ManagementBannerDto(
                banner.getUuid(), banner.getStatus(), lifecycle, banner.getHtmlContent(), banner.getTitle(), banner.getAppearance(),
                banner.getIcon(), banner.isDismissible(), banner.getAudience(), banner.getPlacement(), pageTargets,
                banner.getStartAt(), banner.getEndAt(), banner.getPriority(), banner.getPresentationHash(), banner.getCreatedAt(),
                banner.getCreatedBy(), banner.getUpdatedAt(), banner.getUpdatedBy(), banner.getPublishedAt(), banner.getPublishedBy(),
                banner.getDisabledAt(), banner.getDisabledBy()
            )
        );
    }

    private static Optional<BannerLifecycle> lifecycle(BannerOccurrence banner, Instant now) {
        return switch (banner.getStatus()) {
            case SAVED -> Optional.of(BannerLifecycle.SAVED);
            case DISABLED -> Optional.of(BannerLifecycle.DISABLED);
            case PUBLISHED -> {
                if (banner.getEndAt() != null && !banner.getEndAt().isAfter(now)) {
                    yield Optional.of(BannerLifecycle.EXPIRED);
                }
                if (banner.getStartAt() != null && banner.getStartAt().isAfter(now)) {
                    yield Optional.of(BannerLifecycle.SCHEDULED);
                }
                yield Optional.of(BannerLifecycle.ACTIVE);
            }
            case ARCHIVED -> Optional.empty();
        };
    }
}
