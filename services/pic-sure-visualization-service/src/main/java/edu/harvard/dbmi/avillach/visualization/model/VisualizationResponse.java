package edu.harvard.dbmi.avillach.visualization.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "The distribution charts for one query: a bar chart per categorical concept and a histogram per continuous concept.")
public record VisualizationResponse(
    @Schema(
        description = "One bar chart for each categorical concept in the query that has data. Empty when there is none.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<CategoricalDistributionData> categoricalData,
    @Schema(
        description = "One histogram for each continuous concept in the query that has data. Empty when there is none.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<ContinuousDistributionData> continuousData
) {
}
