package edu.harvard.dbmi.avillach.visualization.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The {@code POST /{backend}/distributions} request body. The path selects the auth or open backend, and query-service picks its HPDS
 * backend from the request path. Unknown properties, such as a {@code hpdsResourceUUID} selector, are ignored on read rather than rejected.
 *
 * @param query the v3 query whose cohort the charts describe
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "The query whose cohort the distribution charts describe.")
public record DistributionRequest(
    @Schema(
        description = "A v3 query. A `FILTER` with `values` asks for a bar chart of its concept, and a `FILTER` with `min` or `max` asks "
            + "for a histogram. A `REQUIRED` or `ANY_RECORD_OF` filter asks for both, so its concept can get two charts. When no filter "
            + "asks for a chart, each `select` path asks for a bar chart. A concept with no data in the cohort gets no chart. "
            + "`expectedResultType` is ignored, because the service sets it on the sub-queries it sends.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull(message = "Request must contain a 'query' field") Query query
) {
}
