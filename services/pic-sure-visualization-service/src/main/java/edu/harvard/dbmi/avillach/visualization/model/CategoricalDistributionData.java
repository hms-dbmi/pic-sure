package edu.harvard.dbmi.avillach.visualization.model;

import java.util.Map;

public record CategoricalDistributionData(
    String conceptPath, String title, boolean continuous, Map<String, ObfuscatedCount> categoricalMap, boolean obfuscated, String xaxisName,
    String yaxisName, Integer chartWidth, Integer chartHeight
) {
}
