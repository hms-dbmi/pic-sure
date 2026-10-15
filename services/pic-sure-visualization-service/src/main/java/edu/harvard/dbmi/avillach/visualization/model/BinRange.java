package edu.harvard.dbmi.avillach.visualization.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Inclusive range a continuous chart spans, independent of the values observed in the cohort. */
@Schema(description = "The range a continuous chart spans, independent of the values observed in the cohort.")
public record BinRange(
    @Schema(description = "Lowest value the chart spans.", example = "0.0") double min,
    @Schema(description = "Highest value the chart spans.", example = "120.0") double max
) {
}
