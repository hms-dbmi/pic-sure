package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

public record PhenotypicFilter(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PhenotypicFilterType phenotypicFilterType,
    @Schema(description = "A concept path this filter must match") String conceptPath,
    @Schema(
        description = "Values to match on for a given `conceptPath`. Cannot be combined with `min` or `max`",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Set<String> values,
    @Schema(
        description = "Minimum value to filter for a given `conceptPath`. Cannot be combined with `values`",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Double min,
    @Schema(
        description = "Maximum value to filter for a given `conceptPath`. Cannot be combined with `values`",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Double max, Boolean not
) implements PhenotypicClause {

    @JsonIgnore
    public boolean isCategoricalFilter() {
        return PhenotypicFilterType.FILTER.equals(phenotypicFilterType) && values != null && !values.isEmpty();
    }

    @JsonIgnore
    public boolean isNumericFilter() {
        return PhenotypicFilterType.FILTER.equals(phenotypicFilterType) && (min != null || max != null);
    }
}
