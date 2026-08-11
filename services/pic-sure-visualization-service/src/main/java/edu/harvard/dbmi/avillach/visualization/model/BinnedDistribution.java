package edu.harvard.dbmi.avillach.visualization.model;


import java.util.Map;

/**
 * The {@code POST /bin/continuous} response: the binned counterpart of {@link ContinuousBinningRequest#continuousData()}.
 *
 * <p>A named wrapper rather than a bare {@code Map} at the response root, so the endpoint has a schema to document and room to grow (e.g.
 * the bin strategy actually chosen) without breaking every caller. Same reason as the request: the two key levels are dynamic, the shape
 * around them is not.
 */
public record BinnedDistribution(Map<String, Map<String, Integer>> bins) {

    public BinnedDistribution {
        bins = bins == null ? Map.of() : bins;
    }
}
