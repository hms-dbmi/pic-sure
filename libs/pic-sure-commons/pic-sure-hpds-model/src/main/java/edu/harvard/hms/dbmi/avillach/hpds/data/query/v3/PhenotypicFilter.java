package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(description = "A filter on one concept path.")
public record PhenotypicFilter(
    @Schema(
        description = "How the concept path is matched.", requiredMode = Schema.RequiredMode.REQUIRED
    ) PhenotypicFilterType phenotypicFilterType,
    @Schema(description = "A concept path this filter must match.", example = "\\demographics\\SEX\\") String conceptPath,
    @Schema(
        description = "Values to match on for a given `conceptPath`. Cannot be combined with `min` or `max`.",
        example = "[\"Male\", \"Female\"]", requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Set<String> values,
    @Schema(
        description = "Minimum value to filter for a given `conceptPath`. Cannot be combined with `values`.", example = "18",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Double min,
    @Schema(
        description = "Maximum value to filter for a given `conceptPath`. Cannot be combined with `values`.", example = "85",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    ) Double max,
    @Schema(description = "Accepted but not applied today. A filter is never negated, which matches the subquery's `not`.") Boolean not
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
