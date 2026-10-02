package edu.harvard.dbmi.avillach.visualization.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "A bar chart of one categorical concept: the participant count for each of its values in the query's cohort.")
public record CategoricalDistributionData(
    @Schema(
        description = "The concept path the chart describes.", example = "\\demographics\\SEX\\",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String conceptPath,
    @Schema(
        description = "The chart title, built from the last two segments of the concept path.", example = "demographics: SEX",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String title,
    @Schema(description = "Always false for a categorical chart.", requiredMode = Schema.RequiredMode.REQUIRED) boolean continuous,
    @Schema(
        description = "The bars, in display order. Each key is a value of the concept, such as `Male`, shortened when it is 45 characters or longer, or `Other` for the values folded together once the concept has more values than the chart shows. Each value is always a count object, on both backends, never a bare number.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Map<String, ObfuscatedCount> categoricalMap,
    @Schema(
        description = "True when the counts came from the open backend and are obfuscated. False for the exact counts of the authorized backend.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean obfuscated,
    @Schema(
        description = "The x axis label: the last segment of the concept path.", example = "SEX",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String xaxisName,
    @Schema(
        description = "The y axis label. Always `Number of Participants`.", example = "Number of Participants",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String yaxisName,
    @Schema(
        description = "The chart width in pixels. The service always sends null and the frontend falls back to 500.", example = "500"
    ) Integer chartWidth,
    @Schema(
        description = "The chart height in pixels. The service always sends null and the frontend falls back to 600.", example = "600"
    ) Integer chartHeight
) {
}
