package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class QueryDecomposer {

    public record SubQueryDescriptor(Query query, ResultType resultType, String chartType) {
    }

    public List<SubQueryDescriptor> decompose(Query query) {
        List<SubQueryDescriptor> subQueries = new ArrayList<>();
        List<PhenotypicFilter> allFilters = query.allFilters();

        Set<String> categoricalPaths = new LinkedHashSet<>();
        Set<String> numericPaths = new LinkedHashSet<>();

        for (PhenotypicFilter filter : allFilters) {
            if (filter.isCategoricalFilter()) {
                categoricalPaths.add(filter.conceptPath());
            } else if (filter.isNumericFilter()) {
                numericPaths.add(filter.conceptPath());
            } else if (
                PhenotypicFilterType.REQUIRED.equals(filter.phenotypicFilterType())
                    || PhenotypicFilterType.ANY_RECORD_OF.equals(filter.phenotypicFilterType())
            ) {
                categoricalPaths.add(filter.conceptPath());
                numericPaths.add(filter.conceptPath());
            }
        }

        // If no filters produced paths, fall back to the query's select field.
        // This handles cases where the frontend sends explicit select paths without
        // matching filter types (e.g. a query with select but only REQUIRED filters).
        if (categoricalPaths.isEmpty() && numericPaths.isEmpty() && !query.select().isEmpty()) {
            categoricalPaths.addAll(query.select());
        }

        if (!categoricalPaths.isEmpty()) {
            Query categoricalQuery = new Query(
                new ArrayList<>(categoricalPaths), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(),
                ResultType.CATEGORICAL_CROSS_COUNT, query.picsureId(), null
            );
            subQueries.add(new SubQueryDescriptor(categoricalQuery, ResultType.CATEGORICAL_CROSS_COUNT, "bar"));
        }

        if (!numericPaths.isEmpty()) {
            Query numericQuery = new Query(
                new ArrayList<>(numericPaths), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(),
                ResultType.CONTINUOUS_CROSS_COUNT, query.picsureId(), null
            );
            subQueries.add(new SubQueryDescriptor(numericQuery, ResultType.CONTINUOUS_CROSS_COUNT, "histogram"));
        }

        return subQueries;
    }
}
