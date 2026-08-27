package edu.harvard.hms.dbmi.avillach.operations.banner;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublishBannerRequest(
    @NotNull @Size(max = 5_000) String htmlContent, @Size(max = 120) String title, @NotNull BannerAppearance appearance,
    @NotNull BannerIcon icon, boolean dismissible, @NotNull BannerAudience audience, @NotNull BannerPlacement placement,
    @NotNull JsonNode pageTargets
) {
}
