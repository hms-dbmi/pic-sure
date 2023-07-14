package edu.harvard.hms.dbmi.avillach.resource.visualization.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
public class CategoricalData extends edu.harvard.hms.dbmi.avillach.resource.visualization.model.VisualizationData {
    Map<String, Integer> categoricalMap;

    public CategoricalData(String title, Map<String, Integer> categoricalMap) {
        super();
        this.setTitle(title);
        this.categoricalMap = categoricalMap;
    }

    public CategoricalData(String title, Map<String, Integer> categoricalMap, String xAxisLabel, String yAxisLabel) {
        super();
        this.setTitle(title);
        this.categoricalMap = categoricalMap;
        this.setXAxisName(xAxisLabel);
        this.setYAxisName(yAxisLabel);
        this.isObfuscated = false;
    }

    public CategoricalData(String title, Map<String, Integer> categoricalMap, String xAxisLabel, String yAxisLabel, boolean isObfuscated) {
        super();
        this.setTitle(title);
        this.categoricalMap = categoricalMap;
        this.setXAxisName(xAxisLabel);
        this.setYAxisName(yAxisLabel);
        this.isObfuscated = isObfuscated;
    }
}
