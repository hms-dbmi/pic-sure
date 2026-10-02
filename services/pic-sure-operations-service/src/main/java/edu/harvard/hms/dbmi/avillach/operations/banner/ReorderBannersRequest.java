package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "The display order to give the active and scheduled banners.")
public record ReorderBannersRequest(
    @Schema(
        description = "Banner ids in the order they should display. An active or scheduled banner left out keeps its place after the listed ones. A repeated id or an id of no banner is answered with 400.",
        example = "[\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"]", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull List<UUID> bannerUuids
) {
}
