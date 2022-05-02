package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;
import org.knowm.xchart.Histogram;

import java.util.Map;

@Data
public class ContinuousData extends VisualizationData {
    Map<Double, Integer> continuousMap;

    public ContinuousData(String title, Map<Double, Integer> continuousMap) {
        super();
        this.setTitle(title);
        this.continuousMap = continuousMap;
    }

    public ContinuousData(String title, Map<Double, Integer> continuousMap, String xAxisLabel, String yAxisLabel) {
        super();
        this.setTitle(title);
        this.setXAxisName(xAxisLabel);
        this.setYAxisName(yAxisLabel);
        this.continuousMap = continuousMap;
    }
}
