package edu.harvard.hms.dbmi.avillach.operations.banner;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The icon shown beside the banner text.")
public enum BannerIcon {
    @Schema(description = "No icon.")
    NONE, @Schema(description = "An information icon.")
    INFORMATION, @Schema(description = "A success icon.")
    SUCCESS, @Schema(description = "A warning icon.")
    WARNING, @Schema(description = "An error icon.")
    ERROR
}
