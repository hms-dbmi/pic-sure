package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.util.Map;

/**
 * Body of the visualization service's {@code /bin/continuous}: per-value counts, the range each concept's chart spans, and the most bins a
 * chart may have.
 */
public record ContinuousBinningRequest(Map<String, Map<String, Integer>> query, Map<String, ChartRange> ranges, Integer maxBins) {
}
