package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PhenotypicFilterValidator {

    private static final Logger log = LoggerFactory.getLogger(PhenotypicFilterValidator.class);

    public void validate(PhenotypicFilter phenotypicFilter, Map<String, ColumnMeta> metaStore) {
        switch (phenotypicFilter.phenotypicFilterType()) {
            case FILTER -> validateFilterFilter(phenotypicFilter, metaStore);
            case REQUIRED -> validateRequiredFilter(phenotypicFilter, metaStore);
            case ANY_RECORD_OF -> validateAnyRecordOfFilter(phenotypicFilter);
        }
    }

    public void validate(AuthorizationFilter authorizationFilter, Map<String, ColumnMeta> metaStore) {
        if (!metaStore.containsKey(authorizationFilter.conceptPath())) {
            throw new IllegalArgumentException(authorizationFilter.conceptPath() + " is not a valid concept path");
        } else if (!metaStore.get(authorizationFilter.conceptPath()).isCategorical()) {
            throw new IllegalArgumentException(
                authorizationFilter.conceptPath() + " is not a categorical variable. Authorization filters must be categorical"
            );
        }
    }

    private void validateFilterFilter(PhenotypicFilter phenotypicFilter, Map<String, ColumnMeta> metaStore) {
        if (
            (phenotypicFilter.min() != null || phenotypicFilter.max() != null) && phenotypicFilter.values() != null
                && !phenotypicFilter.values().isEmpty()
        ) {
            throw new IllegalArgumentException(
                "Filter with concept path " + phenotypicFilter.conceptPath() + " cannot have both categorical values and min/max set"
            );
        } else if (!metaStore.containsKey(phenotypicFilter.conceptPath())) {
            log.debug(phenotypicFilter.conceptPath() + " is not a valid concept path");
        } else if (phenotypicFilter.isCategoricalFilter() && !metaStore.get(phenotypicFilter.conceptPath()).isCategorical()) {
            throw new IllegalArgumentException(phenotypicFilter.conceptPath() + " is not a categorical variable");
        } else if (phenotypicFilter.isNumericFilter() && metaStore.get(phenotypicFilter.conceptPath()).isCategorical()) {
            throw new IllegalArgumentException(phenotypicFilter.conceptPath() + " is not a numeric variable");
        }
    }

    private void validateRequiredFilter(PhenotypicFilter phenotypicFilter, Map<String, ColumnMeta> metaStore) {
        if (phenotypicFilter.min() != null || phenotypicFilter.max() != null || phenotypicFilter.values() != null) {
            throw new IllegalArgumentException(
                "Required filter with concept path " + phenotypicFilter.conceptPath() + " cannot have categorical values or min/max set"
            );
        } else if (!metaStore.containsKey(phenotypicFilter.conceptPath())) {
            log.debug(phenotypicFilter.conceptPath() + " is not a valid concept path");
        }
    }

    private void validateAnyRecordOfFilter(PhenotypicFilter phenotypicFilter) {
        if (phenotypicFilter.min() != null || phenotypicFilter.max() != null || phenotypicFilter.values() != null) {
            throw new IllegalArgumentException(
                "Required filter with concept path " + phenotypicFilter.conceptPath() + " cannot have categorical values or min/max set"
            );
        }
    }
}
