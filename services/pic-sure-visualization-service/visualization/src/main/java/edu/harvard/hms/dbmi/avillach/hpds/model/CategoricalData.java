package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;

import java.util.Map;

@Data
public class CategoricalData extends VisualizationData{
    Map<String, Double> categoricalMap;


    public CategoricalData(String title, Map<String, Double> categoricalMap) {
        super();
        this.setTitle(title);
        this.categoricalMap = categoricalMap;
    }
}
