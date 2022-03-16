package edu.harvard.hms.dbmi.avillach.hpds.model;

import lombok.Data;
import java.util.Map;

@Data
public class ContinuousData extends VisualizationData {
    Map<Double, Integer> continuousMap;

    public ContinuousData(String title, Map<Double, Integer> continuousMap) {
        super();
        this.setTitle(title);
        this.continuousMap = continuousMap;
    }
}
