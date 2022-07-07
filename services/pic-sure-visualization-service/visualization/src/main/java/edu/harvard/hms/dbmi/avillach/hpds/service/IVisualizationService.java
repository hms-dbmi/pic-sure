package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import org.knowm.xchart.internal.chartpart.Chart;

public interface IVisualizationService {
    String createBase64PNG(Chart chart);
}
