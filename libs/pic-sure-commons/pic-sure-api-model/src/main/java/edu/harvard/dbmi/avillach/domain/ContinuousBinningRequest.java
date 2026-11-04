package edu.harvard.dbmi.avillach.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code POST /bin/continuous} request body. The query service's open aggregate path posts its counts here, so the raw counts travel
 * under {@code query}, and any other keys (such as {@code @type} or {@code resourceUUID}) are ignored.
 *
 * @param query raw participant counts to bin, keyed by concept path and then by numeric value
 * @param ranges optional chart range per concept; a concept without one is binned across its observed values
 * @param maxBins optional most bins a chart may have
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Raw continuous counts to group into chart bins, sent in the query envelope the query service uses for every call.")
public record ContinuousBinningRequest(
    @Schema(
        description = "Raw participant counts. Each key is a concept path, such as `\\demographics\\AGE\\`. Each value maps one observed "
            + "numeric value of that concept, written as text such as `45` or `27.5`, to the number of participants with that value. A "
            + "key that is not a number is skipped.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Map<String, Map<String, Integer>> query,
    @Schema(
        description = "Optional range each concept's chart spans, keyed by concept path. A concept without a range is binned across its "
            + "observed values."
    ) Map<String, BinRange> ranges,
    @Schema(
        description = "Optional most bins a chart may have. When a range is supplied, a chart never has more than 50 bins, whatever this "
            + "value is.",
        example = "20"
    ) Integer maxBins
) {
}
