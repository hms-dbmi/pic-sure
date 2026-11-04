package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.util.SetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

@Component
public class PhenotypicQueryExecutor {

    private static Logger log = LoggerFactory.getLogger(PhenotypicQueryExecutor.class);

    private final PhenotypeMetaStore phenotypeMetaStore;

    private final PhenotypicObservationStore phenotypicObservationStore;

    private final int minimumClauseSize;

    /**
     * @param minimumClauseSize when positive, any phenotypic clause, at any level, that matches fewer authorized patients than this matches
     *        nobody. Blocks queries that isolate one or two patients, e.g. P OR (target AND X). 0 (the default) disables it.
     */
    @Autowired
    public PhenotypicQueryExecutor(
        PhenotypeMetaStore phenotypeMetaStore, PhenotypicObservationStore phenotypicObservationStore,
        @Value("${hpds.minimumClauseSize:0}") int minimumClauseSize
    ) {
        this.phenotypeMetaStore = phenotypeMetaStore;
        this.phenotypicObservationStore = phenotypicObservationStore;
        this.minimumClauseSize = minimumClauseSize;
        if (minimumClauseSize > 0) {
            log.info("Phenotypic clauses matching fewer than {} patients will match nobody", minimumClauseSize);
        }
    }

    public Set<Integer> getPatientSet(Query query) {
        if (minimumClauseSize > 0) {
            return getPatientSetWithMinimumClauseSize(query);
        }
        List<PhenotypicClause> mergedClauses = new ArrayList<>();

        List<PhenotypicClause> authorizationClauses = authorizationFiltersToPhenotypicClause(query.authorizationFilters());
        mergedClauses.addAll(authorizationClauses);

        if (query.phenotypicClause() != null) {
            mergedClauses.add(query.phenotypicClause());
        }

        if (!mergedClauses.isEmpty()) {
            PhenotypicSubquery authorizedSubquery = new PhenotypicSubquery(null, mergedClauses, Operator.AND);
            return evaluatePhenotypicClause(authorizedSubquery);
        } else {
            // if there are no phenotypic queries, return all patients
            return phenotypeMetaStore.getPatientIds();
        }
    }

    /** Authorization filters are not subject to the minimum; every other clause is measured within the patients they allow. */
    private Set<Integer> getPatientSetWithMinimumClauseSize(Query query) {
        List<PhenotypicClause> authorizationClauses = authorizationFiltersToPhenotypicClause(query.authorizationFilters());
        Set<Integer> authorized = authorizationClauses.isEmpty() ? null
            : evaluatePhenotypicClause(new PhenotypicSubquery(null, authorizationClauses, Operator.AND));

        if (query.phenotypicClause() == null) {
            return authorized != null ? authorized : phenotypeMetaStore.getPatientIds();
        }
        return evaluateWithMinimum(query.phenotypicClause(), authorized);
    }

    private Set<Integer> evaluateWithMinimum(PhenotypicClause clause, Set<Integer> authorized) {
        Set<Integer> matched = switch (clause) {
            case PhenotypicSubquery subquery -> subquery.phenotypicClauses().parallelStream()
                .map(child -> evaluateWithMinimum(child, authorized)).reduce(getReducer(subquery.operator())).get();
            case PhenotypicFilter filter -> {
                Set<Integer> keys = evaluatePhenotypicFilter(filter);
                yield authorized == null ? keys : SetUtils.intersection(keys, authorized);
            }
        };
        return matched.size() < minimumClauseSize ? new HashSet<>() : matched;
    }

    private List<PhenotypicClause> authorizationFiltersToPhenotypicClause(List<AuthorizationFilter> authorizationFilters) {
        return authorizationFilters.parallelStream().map(authorizationFilter -> {
            return new PhenotypicFilter(
                PhenotypicFilterType.FILTER, authorizationFilter.conceptPath(), authorizationFilter.values(), null, null, null
            );
        }).collect(Collectors.toList());
    }

    private Set<Integer> evaluatePhenotypicClause(PhenotypicClause phenotypicClause) {
        return switch (phenotypicClause) {
            case PhenotypicSubquery phenotypicSubquery -> evaluatePhenotypicSubquery(phenotypicSubquery);
            case PhenotypicFilter phenotypicFilter -> evaluatePhenotypicFilter(phenotypicFilter);
        };
    }

    private Set<Integer> evaluatePhenotypicFilter(PhenotypicFilter phenotypicFilter) {
        return switch (phenotypicFilter.phenotypicFilterType()) {
            case FILTER -> evaluateFilterFilter(phenotypicFilter);
            case REQUIRED -> evaluateRequiredFilter(phenotypicFilter);
            case ANY_RECORD_OF -> evaluateAnyRecordOfFilter(phenotypicFilter);
        };
    }

    private Set<Integer> evaluateAnyRecordOfFilter(PhenotypicFilter phenotypicFilter) {
        Set<String> matchingConcepts = phenotypeMetaStore.getChildConceptPaths(phenotypicFilter.conceptPath());
        Set<Integer> ids = new TreeSet<>();
        for (String concept : matchingConcepts) {
            ids.addAll(phenotypicObservationStore.getAllKeys(concept));
        }
        return ids;
    }

    private Set<Integer> evaluateFilterFilter(PhenotypicFilter phenotypicFilter) {
        if (phenotypicFilter.values() != null) {
            return phenotypicObservationStore.getKeysForValues(phenotypicFilter.conceptPath(), phenotypicFilter.values());
        } else if (phenotypicFilter.max() != null || phenotypicFilter.min() != null) {
            return phenotypicObservationStore
                .getKeysForRange(phenotypicFilter.conceptPath(), phenotypicFilter.min(), phenotypicFilter.max());
        } else {
            throw new IllegalArgumentException("Either values or one of min/max must be set for a filter");
        }
    }

    private Set<Integer> evaluateRequiredFilter(PhenotypicFilter phenotypicFilter) {
        return new HashSet<>(phenotypicObservationStore.getAllKeys(phenotypicFilter.conceptPath()));
    }

    private Set<Integer> evaluatePhenotypicSubquery(PhenotypicSubquery phenotypicSubquery) {
        return phenotypicSubquery.phenotypicClauses().parallelStream().map(this::evaluatePhenotypicClause)
            .reduce(getReducer(phenotypicSubquery.operator()))
            // todo: deal with empty lists
            .get();
    }

    private BinaryOperator<Set<Integer>> getReducer(Operator operator) {
        return switch (operator) {
            case OR -> SetUtils::union;
            case AND -> SetUtils::intersection;
        };
    }

    /**
     * If there are concepts in the list of paths which are already in the cache, push those to the front of the list so that we don't evict
     * and then reload them for concepts which are not yet in the cache.
     *
     * @param paths
     * @param columnCount
     * @return
     */
    public ArrayList<Integer> useResidentCubesFirst(List<String> paths, int columnCount) {
        int x;
        TreeSet<String> pathSet = new TreeSet<>(paths);
        Set<String> residentKeys = Sets.intersection(pathSet, phenotypicObservationStore.getCachedKeys());

        ArrayList<Integer> columnIndex = new ArrayList<Integer>();

        residentKeys.forEach(key -> {
            columnIndex.add(paths.indexOf(key) + 1);
        });

        Sets.difference(pathSet, residentKeys).forEach(key -> {
            columnIndex.add(paths.indexOf(key) + 1);
        });

        for (x = 1; x < columnCount; x++) {
            columnIndex.add(x);
        }
        return columnIndex;
    }


    public Map<String, ColumnMeta> getMetaStore() {
        return phenotypeMetaStore.getMetaStore();
    }

    public Set<Integer> getPatientIds() {
        return phenotypeMetaStore.getPatientIds();
    }
}
