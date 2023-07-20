package edu.harvard.hms.dbmi.avillach.resource.visualization.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.awt.Color;

@Data
@EqualsAndHashCode(callSuper = false)
public class VisualizationData {
    private static final int CHART_WIDTH = 500;
    private static final int CHART_HEIGHT = 600;
    private String title;
    private boolean continuous;
    private Color[] colors;
    private String xAxisName;
    private String yAxisName;
    Integer chartWidth;
    Integer chartHeight;
    boolean isObfuscated;

    public int getChartHeight() {
        if (this.chartHeight == null) {
            return CHART_HEIGHT;
        }
        return this.chartHeight;
    }

    public int getChartWidth() {
        if (this.chartHeight == null) {
            return CHART_WIDTH;
        }
        return this.chartWidth;
    }
}
