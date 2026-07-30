package edu.harvard.dbmi.avillach.visualization.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * The {@code POST /bin/continuous} response: the binned counterpart of {@link ContinuousBinningRequest#continuousData()}.
 *
 * <p>A named wrapper rather than a bare {@code Map} at the response root, so the endpoint has a schema to document and room to grow (e.g.
 * the bin strategy actually chosen) without breaking every caller. Same reason as the request: the two key levels are dynamic, the shape
 * around them is not.
 */
@Schema(description = "Binned continuous value counts, keyed the same way as the request")
public record BinnedDistribution(
    @Schema(
        description = "Binned per-concept counts. Outer key: concept path (the request's keys, unchanged). Inner map: bin label -> count.",
        example = "{\"\\\\demographics\\\\age\\\\\": {\"18.0 - 24.0\": 350, \"24.0 - 30.0\": 120}}"
    ) Map<String, Map<String, Integer>> bins
) {

    public BinnedDistribution {
        bins = bins == null ? Map.of() : bins;
    }
}
