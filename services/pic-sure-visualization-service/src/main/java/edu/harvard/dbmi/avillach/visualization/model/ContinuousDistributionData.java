package edu.harvard.dbmi.avillach.visualization.model;

import java.util.Map;

public record ContinuousDistributionData(
    String title, boolean continuous, Map<String, Integer> continuousMap, boolean obfuscated, String xaxisName, String yaxisName,
    Integer chartWidth, Integer chartHeight
) {
}
