package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.PieChart;

public interface IChartService {
    PieChart createPieChart(CategoricalData chartData);
    CategoryChart createHistogram(ContinuousData chartData);
}
