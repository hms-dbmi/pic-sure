package edu.harvard.dbmi.avillach.visualization.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The {@code POST /{backend}/distributions} request body. The removed resource registry's {@code hpdsResourceUUID} selector is gone. The
 * visualization path selects the auth or open backend, and query-service picks its HPDS backend from the request path. Clients still
 * sending the old field are unaffected because this record ignores unknown properties, so the field is dropped rather than rejected.
 *
 * @param query the v3 query whose cohort the charts describe
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "The query whose cohort the distribution charts describe.")
public record DistributionRequest(
    @Schema(
        description = "A v3 query. One chart is drawn for each concept its phenotypic filters name, or for each `select` path when no "
            + "filter names a concept. `expectedResultType` is ignored, because the service sets it on the sub-queries it sends.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull(message = "Request must contain a 'query' field") Query query
) {
}
