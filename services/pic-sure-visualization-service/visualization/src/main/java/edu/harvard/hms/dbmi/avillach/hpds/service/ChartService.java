package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.PicSureTheme;
import org.knowm.xchart.*;
import org.knowm.xchart.style.PieStyler;
import org.knowm.xchart.style.Styler;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.util.*;
import java.util.List;

@Service
public class ChartService implements IChartService {

    private PicSureTheme theme;
    public ChartService() {
        this.theme = new PicSureTheme();
    }

    public PieChart createPieChart(CategoricalData chartData) {
        String title = (chartData.getTitle().length() >= 96) ?
                        chartData.getTitle().substring(0, 95) + "..." :
                        chartData.getTitle();
        PieChart chart = new PieChartBuilder()
                .title(title)
                .width(chartData.getChartWidth())
                .height(chartData.getChartWidth())
                .build();
        chart.getStyler().setSeriesColors(theme.getSeriesColors());
        chart.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
        chart.getStyler().setChartBackgroundColor(theme.getChartBackgroundColor());
        chart.getStyler().setPlotBackgroundColor(theme.getPlotBackgroundColor());
        chart.getStyler().setPlotBorderColor(new Color(0, 0, 0, 0));
        chart.getStyler().setLegendBorderColor(new Color(0, 0, 0, 0));
        chart.getStyler().setLabelsFont(new Font("Nunito Sans", Font.BOLD, 12));
        chartData.getCategoricalMap().forEach((k, v) -> chart.addSeries(k, v));
        return chart;
    }

    public CategoryChart createHistogram(ContinuousData chartData) {
        CategoryChart chart = new CategoryChartBuilder()
                .width(chartData.getChartWidth())
                .height(chartData.getChartWidth())
                .title(chartData.getTitle())
                .build();
        chart.getStyler().setSeriesColors(theme.getSeriesColors());
        chart.getStyler().setChartBackgroundColor(theme.getChartBackgroundColor());
        chart.getStyler().setPlotBackgroundColor(theme.getPlotBackgroundColor());
        chart.getStyler().setPlotBorderColor(new Color(0, 0, 0, 0));
        chart.getStyler().setLegendBorderColor(new Color(0, 0, 0, 0));
        chart.getStyler().setPlotGridLinesVisible(false);
        chart.getStyler().setAvailableSpaceFill(.9);
        chart.getStyler().setAxisTitleFont(new Font("Nunito Sans", Font.BOLD, 16));
        chart.getStyler().setLegendVisible(false);
        chart.getStyler().setDecimalPattern("#,###.##");
        chart.setXAxisTitle(chartData.getXAxisName());
        chart.setXAxisTitle(chartData.getYAxisName());

        double[] keys = new double[chartData.getContinuousMap().entrySet().size()];
        double[] values = new double[chartData.getContinuousMap().entrySet().size()];
        List<Map.Entry<Double, Integer>> list = new ArrayList<>(chartData.getContinuousMap().entrySet());
        for (int i = 0; i < chartData.getContinuousMap().size(); i++) {
            Map.Entry<Double, Integer> entry = list.get(i);
            keys[i] = entry.getKey();
            values[i] = entry.getValue();
        }
        chart.addSeries(chartData.getTitle(), keys, values);
        return chart;
    }
}
