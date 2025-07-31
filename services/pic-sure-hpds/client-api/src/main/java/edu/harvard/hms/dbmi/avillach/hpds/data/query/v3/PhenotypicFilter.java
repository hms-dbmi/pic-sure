package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public record PhenotypicFilter(
    PhenotypicFilterType phenotypicFilterType, String conceptPath, List<String> values, Double min, Double max, Boolean not
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
