package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.SummaryColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.util.SetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

@Component
public class PhenotypicQueryExecutor {

    private static Logger log = LoggerFactory.getLogger(PhenotypicQueryExecutor.class);

    private final PartitionedPhenotypicObservationStore phenotypicObservationStore;

    private final LoadingCache<String, Set<String>> childConceptCache;

    @Autowired
    public PhenotypicQueryExecutor(
        PartitionedPhenotypicObservationStore phenotypicObservationStore,
        @Value("${CHILD_CONCEPT_CACHE_SIZE:500}") int childConceptCacheSize
    ) {
        this.phenotypicObservationStore = phenotypicObservationStore;
        this.childConceptCache = CacheBuilder.newBuilder().maximumSize(childConceptCacheSize).build(new CacheLoader<>() {
            @Override
            public Set<String> load(String key) {
                return loadChildConceptPaths(key);
            }
        });
    }

    public Set<Integer> getPatientSet(Query query) {
        Set<String> consents = query.consentValues();
        if (query.phenotypicClause() != null) {
            return evaluatePhenotypicClause(query.phenotypicClause(), consents);
        } else {
            // if there are no phenotypic queries, return all patients the caller's consents grant
            return phenotypicObservationStore.getPatientIds(consents);
        }
    }

    private List<PhenotypicClause> authorizationFiltersToPhenotypicClause(List<AuthorizationFilter> authorizationFilters) {
        return authorizationFilters.parallelStream().map(authorizationFilter -> {
            return new PhenotypicFilter(
                PhenotypicFilterType.FILTER, authorizationFilter.conceptPath(), authorizationFilter.values(), null, null, null
            );
        }).collect(Collectors.toList());
    }

    private Set<Integer> evaluatePhenotypicClause(PhenotypicClause phenotypicClause, Set<String> consents) {
        return switch (phenotypicClause) {
            case PhenotypicSubquery phenotypicSubquery -> evaluatePhenotypicSubquery(phenotypicSubquery, consents);
            case PhenotypicFilter phenotypicFilter -> evaluatePhenotypicFilter(phenotypicFilter, consents);
        };
    }

    private Set<Integer> evaluatePhenotypicFilter(PhenotypicFilter phenotypicFilter, Set<String> consents) {
        return switch (phenotypicFilter.phenotypicFilterType()) {
            case FILTER -> evaluateFilterFilter(phenotypicFilter, consents);
            case REQUIRED -> evaluateRequiredFilter(phenotypicFilter, consents);
            case ANY_RECORD_OF -> evaluateAnyRecordOfFilter(phenotypicFilter, consents);
        };
    }

    private Set<Integer> evaluateAnyRecordOfFilter(PhenotypicFilter phenotypicFilter, Set<String> consents) {
        Set<String> matchingConcepts = getChildConceptPaths(phenotypicFilter.conceptPath());
        Set<Integer> ids = new TreeSet<>();
        for (String concept : matchingConcepts) {
            ids.addAll(phenotypicObservationStore.getAllKeys(concept, consents));
        }
        return ids;
    }

    private Set<Integer> evaluateFilterFilter(PhenotypicFilter phenotypicFilter, Set<String> consents) {
        if (phenotypicFilter.values() != null) {
            return phenotypicObservationStore.getKeysForValues(phenotypicFilter.conceptPath(), phenotypicFilter.values(), consents);
        } else if (phenotypicFilter.max() != null || phenotypicFilter.min() != null) {
            return phenotypicObservationStore
                .getKeysForRange(phenotypicFilter.conceptPath(), phenotypicFilter.min(), phenotypicFilter.max(), consents);
        } else {
            throw new IllegalArgumentException("Either values or one of min/max must be set for a filter");
        }
    }

    private Set<Integer> evaluateRequiredFilter(PhenotypicFilter phenotypicFilter, Set<String> consents) {
        return new HashSet<>(phenotypicObservationStore.getAllKeys(phenotypicFilter.conceptPath(), consents));
    }

    private Set<Integer> evaluatePhenotypicSubquery(PhenotypicSubquery phenotypicSubquery, Set<String> consents) {
        return phenotypicSubquery.phenotypicClauses().parallelStream().map(clause -> evaluatePhenotypicClause(clause, consents))
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


    public Map<String, SummaryColumnMeta> getMetaStore() {
        return phenotypicObservationStore.getMetaStore();
    }

    public Set<Integer> getPatientIds(Set<String> consents) {
        return phenotypicObservationStore.getPatientIds(consents);
    }

    public Set<String> getChildConceptPaths(String conceptPath) {
        try {
            return childConceptCache.get(conceptPath);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<String> loadChildConceptPaths(String conceptPath) {
        return phenotypicObservationStore.getMetaStore().keySet().stream().filter(column -> column.startsWith(conceptPath))
            .collect(Collectors.toSet());
    }
}
