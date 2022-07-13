package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;

import java.util.Map;

@Data
public class CategoricalData extends VisualizationData{
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
    }
}
