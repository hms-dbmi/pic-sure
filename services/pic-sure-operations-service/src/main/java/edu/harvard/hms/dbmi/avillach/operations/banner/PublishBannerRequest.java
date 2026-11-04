package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    description = "The authorable fields of a banner. Publishing, saving a draft, updating, publishing a draft and restoring all take this body."
)
public record PublishBannerRequest(
    @Schema(
        description = "The banner body as HTML, at most 5000 characters.",
        example = "<p>PIC-SURE will be unavailable on Saturday from 8 PM to 10 PM ET.</p>", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull @Size(max = 5_000) String htmlContent,
    @Schema(
        description = "The banner title, at most 120 characters. Surrounding whitespace is removed and a blank title is stored as none.",
        example = "Scheduled maintenance"
    ) @Size(max = 120) String title,
    @Schema(
        description = "The color treatment of the banner.", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull BannerAppearance appearance,
    @Schema(description = "The icon shown beside the banner text.", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull BannerIcon icon,
    @Schema(description = "Whether a visitor can close the banner. False when absent.") boolean dismissible,
    @Schema(description = "Which visitors see the banner.", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull BannerAudience audience,
    @Schema(
        description = "Where on the page the banner is rendered.", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull BannerPlacement placement,
    @Schema(
        description = "The pages the banner appears on. At least one target is needed, and an ALL target cannot be combined with any other.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull List<BannerPageTarget> pageTargets,
    @Schema(
        description = "When the banner starts showing, to the minute and not in the past. When absent, a published banner starts now and a draft has no start.",
        example = "2026-09-30T14:05:00Z"
    ) Instant startAt,
    @Schema(
        description = "When the banner stops showing, to the minute and after the start. When absent the banner has no end.",
        example = "2026-10-07T14:05:00Z"
    ) Instant endAt
) {
    public PublishBannerRequest(
        String htmlContent, String title, BannerAppearance appearance, BannerIcon icon, boolean dismissible, BannerAudience audience,
        BannerPlacement placement, List<BannerPageTarget> pageTargets
    ) {
        this(htmlContent, title, appearance, icon, dismissible, audience, placement, pageTargets, null, null);
    }
}
