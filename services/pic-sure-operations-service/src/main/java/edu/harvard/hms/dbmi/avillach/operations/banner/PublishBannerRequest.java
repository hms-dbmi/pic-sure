package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublishBannerRequest(
    @NotNull @Size(max = 5_000) String htmlContent, @Size(max = 120) String title, @NotNull BannerAppearance appearance,
    @NotNull BannerIcon icon, boolean dismissible, @NotNull BannerAudience audience, @NotNull BannerPlacement placement,
    @NotNull List<BannerPageTarget> pageTargets, Instant startAt, Instant endAt
) {
    public PublishBannerRequest(
        String htmlContent, String title, BannerAppearance appearance, BannerIcon icon, boolean dismissible, BannerAudience audience,
        BannerPlacement placement, List<BannerPageTarget> pageTargets
    ) {
        this(htmlContent, title, appearance, icon, dismissible, audience, placement, pageTargets, null, null);
    }
}
