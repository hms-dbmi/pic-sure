package edu.harvard.hms.dbmi.avillach.operations.banner;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The stored state of a banner.")
public enum BannerStatus {
    @Schema(description = "A draft that has not been published.")
    SAVED, @Schema(description = "Published. Whether it is showing depends on its schedule.")
    PUBLISHED, @Schema(description = "Taken down by an administrator.")
    DISABLED, @Schema(description = "Archived. The list endpoints never return an archived banner.")
    ARCHIVED
}
