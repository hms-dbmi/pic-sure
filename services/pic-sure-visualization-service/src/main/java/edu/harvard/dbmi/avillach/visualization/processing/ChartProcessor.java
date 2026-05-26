package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ChartData;
import edu.harvard.dbmi.avillach.visualization.model.ChartType;

import java.util.List;
import java.util.Map;

public interface ChartProcessor {

    ChartType chartType();

    /**
     * Convert pre-processed cross-count data into Plotly-ready chart objects. Data passed here is already binned (for histograms) and
     * aggregated (for bar charts) — the processor only handles Plotly trace/layout generation.
     */
    List<ChartData> process(Map<String, Map<String, Integer>> crossCounts, boolean isObfuscated);

    /**
     * Pre-process raw cross-count data before rendering. Called by the orchestrator for authorized access data. Open access data is already
     * pre-processed by HPDS and skips this step.
     *
     * <p> Default implementation returns data unchanged — processors that need pre-processing (binning, top-N aggregation) override this.
     */
    default Map<String, Map<String, Integer>> preProcess(Map<String, Map<String, Integer>> crossCounts) {
        return crossCounts;
    }

    default String getChartTitle(String filterKey) {
        String[] titleParts = filterKey.split("\\\\");
        if (titleParts.length >= 2) {
            return "Variable distribution of " + titleParts[titleParts.length - 2] + ": " + titleParts[titleParts.length - 1];
        }
        return filterKey;
    }

    default String createXAxisLabel(String title) {
        int lastSpace = title.lastIndexOf(' ');
        if (lastSpace >= 0 && lastSpace < title.length() - 1) {
            return title.substring(lastSpace + 1);
        }
        return title;
    }
}
