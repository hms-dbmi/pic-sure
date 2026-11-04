package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A banner as administrators manage it: its content, schedule, display order and edit history.")
public record ManagementBannerDto(
    @Schema(
        description = "The id of the banner.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The stored state of the banner. ARCHIVED never appears here because archived banners are not listed.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) BannerStatus status,
    @Schema(
        description = "Where the banner stands at the time of the response, derived from its status and its schedule.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) BannerLifecycle lifecycle,
    @Schema(
        description = "The banner body as HTML.", example = "<p>PIC-SURE will be unavailable on Saturday from 8 PM to 10 PM ET.</p>",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String htmlContent,
    @Schema(description = "The banner title, or null when it has none.", example = "Scheduled maintenance") String title,
    @Schema(description = "The color treatment of the banner.", requiredMode = Schema.RequiredMode.REQUIRED) BannerAppearance appearance,
    @Schema(description = "The icon shown beside the banner text.", requiredMode = Schema.RequiredMode.REQUIRED) BannerIcon icon,
    @Schema(description = "Whether a visitor can close the banner.", requiredMode = Schema.RequiredMode.REQUIRED) boolean dismissible,
    @Schema(description = "Which visitors see the banner.", requiredMode = Schema.RequiredMode.REQUIRED) BannerAudience audience,
    @Schema(
        description = "Where on the page the banner is rendered.", requiredMode = Schema.RequiredMode.REQUIRED
    ) BannerPlacement placement,
    @Schema(
        description = "The pages the banner appears on, in canonical order: ALL first, then EXACT, PARAMETERIZED and SUBTREE, each by path.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<BannerPageTarget> pageTargets,
    @Schema(
        description = "When the banner starts showing. Null on a draft saved without a start.", example = "2026-09-30T14:05:00Z"
    ) Instant startAt,
    @Schema(description = "When the banner stops showing, or null when it has no end.", example = "2026-10-07T14:05:00Z") Instant endAt,
    @Schema(
        description = "The display position among active and scheduled banners. The lowest number shows first. Null on a draft that was never published.",
        example = "1"
    ) Integer priority,
    @Schema(
        description = "A SHA-256 hex digest of what a visitor sees: the body, title, appearance, icon, dismissible flag, audience, placement and page targets. It changes when any of them changes.",
        example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", requiredMode = Schema.RequiredMode.REQUIRED
    ) String presentationHash,
    @Schema(
        description = "When the banner was created.", example = "2026-09-30T14:05:00Z", requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant createdAt,
    @Schema(
        description = "The gateway user id of the administrator who created the banner.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String createdBy,
    @Schema(
        description = "When the banner was last changed.", example = "2026-09-30T14:05:00Z", requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant updatedAt,
    @Schema(
        description = "The gateway user id of the administrator who last changed the banner.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) String updatedBy,
    @Schema(description = "When the banner was published, or null on a draft.", example = "2026-09-30T14:05:00Z") Instant publishedAt,
    @Schema(
        description = "The gateway user id of the administrator who published the banner, or null on a draft.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) String publishedBy,
    @Schema(description = "When the banner was disabled, or null when it never was.", example = "2026-09-30T14:05:00Z") Instant disabledAt,
    @Schema(
        description = "The gateway user id of the administrator who disabled the banner, or null when it never was.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) String disabledBy,
    @Schema(
        description = "The id of the disabled or expired banner this one was restored from, or null when it was not created by a restore.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) UUID restoredFromUuid
) {
    static Optional<ManagementBannerDto> from(BannerOccurrence banner, Instant now) {
        List<BannerPageTarget> pageTargets = banner.getPageTargets();
        if (pageTargets == null) {
            return Optional.empty();
        }
        return lifecycle(banner, now).map(
            lifecycle -> new ManagementBannerDto(
                banner.getUuid(), banner.getStatus(), lifecycle, banner.getHtmlContent(), banner.getTitle(), banner.getAppearance(),
                banner.getIcon(), banner.isDismissible(), banner.getAudience(), banner.getPlacement(), pageTargets, banner.getStartAt(),
                banner.getEndAt(), banner.getPriority(), banner.getPresentationHash(), banner.getCreatedAt(), banner.getCreatedBy(),
                banner.getUpdatedAt(), banner.getUpdatedBy(), banner.getPublishedAt(), banner.getPublishedBy(), banner.getDisabledAt(),
                banner.getDisabledBy(), banner.getRestoredFromUuid()
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
