package edu.harvard.hms.dbmi.avillach.operations.banner;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How a page target matches the page a visitor is on.")
public enum BannerPageTargetKind {
    @Schema(description = "Every page. The target has no path and cannot be combined with other targets.")
    ALL, @Schema(description = "The one page whose path equals the target path.")
    EXACT, @Schema(description = "Every page whose path matches the target path, where each [name] segment stands for any one segment.")
    PARAMETERIZED, @Schema(description = "The page at the target path and every page beneath it.")
    SUBTREE
}
