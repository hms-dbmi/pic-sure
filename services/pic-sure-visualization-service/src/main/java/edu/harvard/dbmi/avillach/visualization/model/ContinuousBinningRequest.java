package edu.harvard.dbmi.avillach.visualization.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * The {@code POST /bin/continuous} request body. The query service's open aggregate path posts its {@code GeneralQueryRequest} envelope
 * here, so the raw counts travel under {@code query} and the envelope's other keys ({@code @type}, {@code resourceUUID}) are ignored.
 *
 * @param query raw participant counts to bin, keyed by concept path and then by numeric value
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Raw continuous counts to group into chart bins, sent in the query envelope the query service uses for every call.")
public record ContinuousBinningRequest(
    @Schema(
        description = "Raw participant counts. Each key is a concept path, such as `\\demographics\\AGE\\`. Each value maps one observed "
            + "numeric value of that concept, written as text such as `45` or `27.5`, to the number of participants with that value. A "
            + "key that is not a number is skipped.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull(message = "Request must contain a 'query' field") Map<String, Map<String, Integer>> query
) {
}
