package edu.harvard.dbmi.avillach.visualization.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * The {@code POST /bin/continuous} request body: the raw per-concept value counts to bin.
 *
 * <p>The field used to be called {@code query}, which it never was -- there is no filtering, no result type and no HPDS hop behind this
 * endpoint, only the histogram binning of counts the caller already has. The retired v1 envelope fields
 * ({@code resourceUUID}/{@code resourceCredentials}) are rejected rather than ignored: unmodelled properties fail deserialization, so a
 * client on the old shape gets a 400 instead of a silent no-op.
 *
 * <p>The nested maps stay maps by design: both key levels are dynamic (concept paths, then value labels), so there is nothing to name.
 */
public record ContinuousBinningRequest(
    @Schema(
        description = "Per-concept continuous value counts to bin. Outer key: concept path. Inner map: value label -> count.",
        example = "{\"\\\\demographics\\\\age\\\\\": {\"18.0\": 100, \"19.0\": 250}}"
    ) @NotNull(message = "Request must contain a 'continuousData' field") Map<String, Map<String, Integer>> continuousData
) {
}
