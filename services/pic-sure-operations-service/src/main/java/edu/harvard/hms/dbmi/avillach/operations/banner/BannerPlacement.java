package edu.harvard.hms.dbmi.avillach.operations.banner;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where on the page a banner is rendered.")
public enum BannerPlacement {
    @Schema(description = "Across the top of the site, above the page content.")
    SITE_TOP
}
