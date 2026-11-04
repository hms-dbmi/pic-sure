package edu.harvard.hms.dbmi.avillach.operations.banner;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where a banner stands at the time of the response, derived from its status and its schedule.")
public enum BannerLifecycle {
    @Schema(description = "Published and inside its schedule, so it is showing now.")
    ACTIVE, @Schema(description = "Published with a start time that is still in the future.")
    SCHEDULED, @Schema(description = "A draft that has not been published.")
    SAVED, @Schema(description = "Taken down by an administrator.")
    DISABLED, @Schema(description = "Published with an end time that has passed.")
    EXPIRED
}
