package edu.harvard.hms.dbmi.avillach.operations.banner;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The color treatment of a banner, named after the color roles of the UI theme.")
public enum BannerAppearance {
    @Schema(description = "The theme's primary color.")
    PRIMARY, @Schema(description = "The theme's secondary color.")
    SECONDARY, @Schema(description = "The theme's tertiary color.")
    TERTIARY, @Schema(description = "The theme's success color, for good news.")
    SUCCESS, @Schema(description = "The theme's warning color, for notices that need attention.")
    WARNING, @Schema(description = "The theme's error color, for outages and failures.")
    ERROR, @Schema(description = "The theme's neutral surface color.")
    SURFACE
}
