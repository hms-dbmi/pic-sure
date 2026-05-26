package edu.harvard.dbmi.avillach.visualization.model;

import java.util.List;
import java.util.Map;

public record ChartData(
    String chartType, String title, boolean isObfuscated, List<Map<String, Object>> traces, Map<String, Object> layout
) {
}
