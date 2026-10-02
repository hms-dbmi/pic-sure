package edu.harvard.dbmi.avillach.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body the visualization service answers {@code POST /bin/continuous} with, and the body the query service's open aggregate path reads
 * back. It lives in this module because both services exchange it.
 *
 * <p>Unknown properties are ignored on read, so the producer can add a component without breaking a reader built against this version.
 *
 * @param bins binned participant counts, keyed by concept path and then by bin label, in ascending bin order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Continuous values grouped into chart bins, one entry per concept path.")
public record ContinuousBinningResponse(
    @Schema(
        description = "Binned participant counts. Each key is a concept path, such as `\\demographics\\AGE\\`. Each value maps a bin label "
            + "to the number of participants in that bin, in ascending bin order. A bin label is a range such as `20.0 - 40.0`, a single "
            + "value such as `45.0`, or the open last bin such as `80.0 +`.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Map<String, Map<String, Integer>> bins
) {
}
