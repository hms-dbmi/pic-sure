package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A banner that is showing now, with everything a page needs to render it.")
public record ActiveBannerDto(
    @Schema(
        description = "The id of the banner.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
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
        description = "The display position among the banners showing now. The lowest number shows first.", example = "1",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Integer priority,
    @Schema(
        description = "A SHA-256 hex digest of what a visitor sees: the body, title, appearance, icon, dismissible flag, audience, placement and page targets. It changes when any of them changes, so a client can tell an edited banner from one the visitor already dismissed.",
        example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", requiredMode = Schema.RequiredMode.REQUIRED
    ) String presentationHash
) {
    static Optional<ActiveBannerDto> from(BannerOccurrence banner) {
        List<BannerPageTarget> pageTargets = banner.getPageTargets();
        if (pageTargets == null) {
            return Optional.empty();
        }
        return Optional.of(
            new ActiveBannerDto(
                banner.getUuid(), banner.getHtmlContent(), banner.getTitle(), banner.getAppearance(), banner.getIcon(),
                banner.isDismissible(), banner.getAudience(), banner.getPlacement(), pageTargets, banner.getPriority(),
                banner.getPresentationHash()
            )
        );
    }
}
