package edu.harvard.dbmi.avillach.visualization.model;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * @param ranges optional chart range per concept; a concept without one is binned across its observed values
 * @param maxBins optional most bins a chart may have
 */
public record ContinuousBinningRequest(
    @NotNull(message = "Request must contain a 'query' field") Map<String, Map<String, Integer>> query, Map<String, BinRange> ranges,
    Integer maxBins
) {
}
