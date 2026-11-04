package edu.harvard.dbmi.avillach.dictionary.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One column of the study dashboard.")
public record DashboardColumn(
    @Schema(
        description = "Key under which every row holds this column's value.", example = "abbreviation",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String dataElement,
    @Schema(
        description = "Column heading shown to users.", example = "Abbreviation", requiredMode = Schema.RequiredMode.REQUIRED
    ) String label
) {
}
