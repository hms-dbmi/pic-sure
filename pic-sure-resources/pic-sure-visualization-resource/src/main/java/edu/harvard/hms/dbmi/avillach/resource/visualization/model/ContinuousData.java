package edu.harvard.hms.dbmi.avillach.resource.visualization.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
public class ContinuousData extends edu.harvard.hms.dbmi.avillach.resource.visualization.model.VisualizationData {
    Map<String, Integer> continuousMap;

    public ContinuousData(String title, Map<String, Integer> continuousMap) {
        super();
        this.setTitle(title);
        this.continuousMap = continuousMap;
    }

    public ContinuousData(String title, Map<String, Integer> continuousMap, String xAxisLabel, String yAxisLabel) {
        super();
        this.setTitle(title);
        this.setXAxisName(xAxisLabel);
        this.setYAxisName(yAxisLabel);
        this.continuousMap = continuousMap;
    }

    public ContinuousData(String title, Map<String, Integer> continuousMap, String xAxisLabel, String yAxisLabel, boolean isObfuscated) {
        super();
        this.setTitle(title);
        this.setXAxisName(xAxisLabel);
        this.setYAxisName(yAxisLabel);
        this.setObfuscated(isObfuscated);
        this.continuousMap = continuousMap;
    }
}
