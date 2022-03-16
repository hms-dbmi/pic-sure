package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.PicSureTheme;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.knowm.xchart.style.Styler;
import org.springframework.stereotype.Service;

@Service
public class ChartService implements IChartService {

    private PicSureTheme theme;
    public ChartService() {
        this.theme = new PicSureTheme();
    }

    public PieChart createPieChart(CategoricalData chartData) {
        PieChart chart = new PieChartBuilder().width(chartData.getChartWidth()).height(chartData.getChartWidth()).title(chartData.getTitle()).theme(Styler.ChartTheme.GGPlot2).build();
        chart.getStyler().setSeriesColors(theme.getSeriesColors());
        chartData.getCategoricalMap().forEach((k, v) -> chart.addSeries(k, v));
        return chart;
    }

    public CategoryChart createHistogram(ContinuousData chartData) {
        CategoryChart chart = new CategoryChartBuilder()
                .width(chartData.getChartWidth())
                .height(chartData.getChartWidth())
                .title(chartData.getTitle())
                .theme(Styler.ChartTheme.GGPlot2).build();
        chart.getStyler().setSeriesColors(theme.getSeriesColors());
        double[] keys = new double[chartData.getContinuousMap().entrySet().size()];
        double[] values = new double[chartData.getContinuousMap().entrySet().size()];
        for (int i = 0; i < chartData.getContinuousMap().size(); i++) {
            keys[i] = chartData.getContinuousMap().entrySet().stream().skip(i).findFirst().get().getKey();
            values[i] = chartData.getContinuousMap().entrySet().stream().skip(i).findFirst().get().getValue();
        }
        chart.addSeries(chartData.getTitle(), keys, values);
        return chart;
    }
}
