package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;

import java.awt.Color;

@Data

public class VisualizationData {
    private static final int CHART_WIDTH = 600;
    private static final int CHART_HEIGHT = 1000;
    private String title;
    private boolean continuous;
    private Color[] colors;
    private String xAxisName;
    private String yAxisName;
    Integer chartWidth;
    Integer chartHeight;

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
