package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.hms.dbmi.avillach.hpds.model.CategoricalData;
import edu.harvard.hms.dbmi.avillach.hpds.model.ContinuousData;
import edu.harvard.hms.dbmi.avillach.hpds.model.domain.PicSureTheme;
import org.knowm.xchart.*;
import org.knowm.xchart.internal.chartpart.Chart;
import org.knowm.xchart.style.AxesChartStyler;
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
        this.setUpPicSureStyler(chart);
        chart.getStyler().setSeriesColors(theme.getSeriesColors());
        chart.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
        chart.getStyler().setLabelsFont(new Font("Nunito Sans", Font.BOLD, 12));
        chartData.getCategoricalMap().forEach((k, v) -> chart.addSeries(k, v));
        return chart;
    }

    public CategoryChart createCategoryBar(CategoricalData chartData) {
        String title = (chartData.getTitle().length() >= 96) ?
                chartData.getTitle().substring(0, 95) + "..." :
                chartData.getTitle();
        CategoryChart chart = new CategoryChartBuilder()
                .width(chartData.getChartWidth())
                .height(chartData.getChartWidth())
                .title(chartData.getTitle())
                .build();
        this.setUpPicSureStyler(chart);
        chart.getStyler().setSeriesColors(new Color[]{new Color(26, 86, 140)});
        chart.getStyler().setAxisTickLabelsFont(new Font("Nunito Sans", Font.PLAIN, 11));
        chart.setXAxisTitle(chartData.getXAxisName());
        chart.setYAxisTitle(chartData.getYAxisName());
        chart.getStyler().setLegendVisible(false);
        chart.getStyler().setLabelsVisible(true);
        chart.getStyler().setLabelsPosition(.5);
        chart.getStyler().setXAxisLabelRotation(90);
        chart.getStyler().setXAxisLabelAlignmentVertical(AxesChartStyler.TextAlignment.Right);
        chart.getStyler().setXAxisMaxLabelCount(8);

        chart.addSeries(title, new ArrayList<>(chartData.getCategoricalMap().keySet()), new ArrayList<>(chartData.getCategoricalMap().values()));
        return chart;
    }

    public CategoryChart createHistogram(ContinuousData chartData) {
        String title = (chartData.getTitle().length() >= 96) ?
                chartData.getTitle().substring(0, 95) + "..." :
                chartData.getTitle();
        CategoryChart chart = new CategoryChartBuilder()
                .width(chartData.getChartWidth())
                .height(chartData.getChartWidth())
                .title(chartData.getTitle())
                .build();
        this.setUpPicSureStyler(chart);
        chart.getStyler().setSeriesColors(theme.getSeriesColors());
        chart.getStyler().setPlotGridLinesVisible(false);
        chart.getStyler().setAvailableSpaceFill(.9);
        chart.getStyler().setAxisTitleFont(new Font("Nunito Sans", Font.BOLD, 16));
        chart.getStyler().setLegendVisible(false);
        chart.setXAxisTitle(chartData.getXAxisName());
        chart.setYAxisTitle(chartData.getYAxisName());

        chart.addSeries(title, new ArrayList<>(chartData.getContinuousMap().keySet()), new ArrayList<>(chartData.getContinuousMap().values()));
        return chart;
    }

    private void setUpPicSureStyler(Chart chart) {
        chart.getStyler().setChartBackgroundColor(theme.getChartBackgroundColor());
        chart.getStyler().setPlotBackgroundColor(theme.getPlotBackgroundColor());
        chart.getStyler().setPlotBorderColor(new Color(0, 0, 0, 0));
        chart.getStyler().setLegendBorderColor(new Color(0, 0, 0, 0));
        chart.getStyler().setDecimalPattern("#,###.##");
    }
}
