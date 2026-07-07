package edu.harvard.dbmi.avillach.visualization.model;

import java.util.List;

public record VisualizationResponse(List<CategoricalDistributionData> categoricalData, List<ContinuousDistributionData> continuousData) {
}
