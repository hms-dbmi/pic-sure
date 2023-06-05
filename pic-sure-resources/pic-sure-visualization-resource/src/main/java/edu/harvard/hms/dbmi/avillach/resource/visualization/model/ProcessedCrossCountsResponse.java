package edu.harvard.hms.dbmi.avillach.resource.visualization.model;

import java.util.List;

public class ProcessedCrossCountsResponse {
    List<CategoricalData> categoricalData;
    List<ContinuousData> continuousData;

    public List<CategoricalData> getCategoricalData() {
        if (categoricalData == null) {
            categoricalData = new java.util.ArrayList<>();
        }
        return categoricalData;
    }

    public List<ContinuousData> getContinuousData() {
        if (continuousData == null) {
            continuousData = new java.util.ArrayList<>();
        }
        return continuousData;
    }
}
