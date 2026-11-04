package edu.harvard.hms.dbmi.avillach.operations.banner;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Which visitors see a banner.")
public enum BannerAudience {
    @Schema(description = "Every visitor, signed in or not.")
    EVERYONE, @Schema(description = "Only visitors who are signed in.")
    SIGNED_IN, @Schema(description = "Only visitors who are not signed in.")
    SIGNED_OUT
}
