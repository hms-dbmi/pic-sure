package edu.harvard.dbmi.avillach.visualization.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "A histogram of one continuous concept: the participant count in each bin of its values in the query's cohort.")
public record ContinuousDistributionData(
    @Schema(
        description = "The concept path the chart describes.", example = "\\demographics\\AGE\\",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String conceptPath,
    @Schema(
        description = "The chart title, built from the last two segments of the concept path.", example = "demographics: AGE",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String title,
    @Schema(description = "Always true for a continuous chart.", requiredMode = Schema.RequiredMode.REQUIRED) boolean continuous,
    @Schema(
        description = "The bins, in ascending order. Each key is a bin label: a range such as `20.0 - 40.0`, a single value such as `45.0`, or the open last bin such as `80.0 +`. Each value is always a count object, on both backends, never a bare number.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Map<String, ObfuscatedCount> continuousMap,
    @Schema(
        description = "True when the counts came from the open backend and are obfuscated. False for the exact counts of the authorized backend.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean obfuscated,
    @Schema(
        description = "The x axis label: the last segment of the concept path.", example = "AGE",
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
