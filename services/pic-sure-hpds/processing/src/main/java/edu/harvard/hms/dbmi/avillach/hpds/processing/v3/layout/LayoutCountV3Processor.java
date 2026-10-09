package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import edu.harvard.hms.dbmi.avillach.hpds.data.genomic.VariantUtils;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PhenotypicCountProcessor;
import org.roaringbitmap.RoaringBitmap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The v3 sync count types over the layout store, with the same inclusion rules and result shapes as {@code CountV3Processor}, so
 * {@code PicSureV3Service} serializes them identically. Cohorts and per-concept patient sets are roaring bitmaps, counts are bitmap
 * intersections, and the continuous and observation counts walk the decoded arrays directly.
 *
 * <p>Genomic filters are refused with {@link UnsupportedOperationException}; the layout store holds no genomic data.
 */
@Component
@ConditionalOnProperty(name = StoreFormat.PROPERTY, havingValue = StoreFormat.LAYOUT)
public class LayoutCountV3Processor implements PhenotypicCountProcessor {

    private final LayoutPhenotypicQueryExecutor executor;

    private final LayoutPhenotypicObservationStore store;

    /**
     * @param executor evaluates cohorts
     * @param store the layout store
     */
    @Autowired
    public LayoutCountV3Processor(LayoutPhenotypicQueryExecutor executor, LayoutPhenotypicObservationStore store) {
        this.executor = executor;
        this.store = store;
    }

    private RoaringBitmap cohort(Query query) {
        if (!query.genomicFilters().isEmpty()) {
            throw new UnsupportedOperationException("Genomic filters are not supported by the layout phenotype store");
        }
        return executor.getPatientSet(query);
    }

    /**
     * @param query the query
     * @return the cohort size
     */
    @Override
    public int runCounts(Query query) {
        return cohort(query).getCardinality();
    }

    /**
     * For each {@code select} path, the number of cohort patients with a value for it: 0 for an unknown path, -1 when its block cannot be
     * loaded. Keys follow {@code select} order.
     *
     * @param query the query
     * @return path to count
     */
    @Override
    public Map<String, Integer> runCrossCounts(Query query) {
        RoaringBitmap cohort = cohort(query);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String concept : query.select()) {
            try {
                counts.put(concept, RoaringBitmap.andCardinality(store.getAllKeys(concept), cohort));
            } catch (RuntimeException e) {
                counts.put(concept, -1);
            }
        }
        return counts;
    }

    /**
     * For each concept named by a value filter, or by a REQUIRED filter on a categorical concept (variant-spec paths excluded), the number
     * of cohort patients per category. A category is listed when its count is above zero or a value filter named it. A numeric or unknown
     * concept gets an empty map.
     *
     * @param query the query
     * @return path to category to count, both in Java string order
     */
    @Override
    public Map<String, Map<String, Integer>> runCategoryCrossCounts(Query query) {
        RoaringBitmap cohort = cohort(query);
        Map<String, List<PhenotypicFilter>> filtersByConcept = query.allFilters().stream().filter(this::isCategoryCrossCountFilter)
            .collect(Collectors.groupingBy(PhenotypicFilter::conceptPath));

        Map<String, Map<String, Integer>> categoryCounts = new TreeMap<>();
        for (Map.Entry<String, List<PhenotypicFilter>> entry : filtersByConcept.entrySet()) {
            Set<String> calledOutValues = entry.getValue().stream().filter(PhenotypicFilter::isCategoricalFilter)
                .flatMap(filter -> filter.values().stream()).collect(Collectors.toSet());
            Map<String, Integer> varCount = new TreeMap<>();
            if (store.getBlock(entry.getKey()).orElse(null) instanceof CategoricalLayoutBlock block) {
                for (int i = 0; i < block.dictionarySize(); i++) {
                    int count = RoaringBitmap.andCardinality(block.bitmap(i), cohort);
                    String category = block.value(i);
                    if (count > 0 || calledOutValues.contains(category)) {
                        varCount.put(category, count);
                    }
                }
            }
            categoryCounts.put(entry.getKey(), varCount);
        }
        return categoryCounts;
    }

    private boolean isCategoryCrossCountFilter(PhenotypicFilter filter) {
        if (VariantUtils.pathIsVariantSpec(filter.conceptPath())) {
            return false;
        }
        if (filter.isCategoricalFilter()) {
            return true;
        }
        return PhenotypicFilterType.REQUIRED.equals(filter.phenotypicFilterType())
            && store.concept(filter.conceptPath()).map(LayoutConcept::categorical).orElse(false);
    }

    /**
     * For each numeric concept any filter names, the number of cohort observations per distinct value. Values are in {@code Double.compare}
     * order, the order of the javabin processor's {@code TreeMap}, and are read from the presorted values array.
     *
     * @param query the query
     * @return path to value to count
     */
    @Override
    public Map<String, Map<Double, Integer>> runContinuousCrossCounts(Query query) {
        RoaringBitmap cohort = cohort(query);
        Set<String> conceptPaths = query.allFilters().stream().map(PhenotypicFilter::conceptPath)
            .filter(path -> store.concept(path).map(concept -> !concept.categorical()).orElse(false))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Map<Double, Integer>> conceptMap = new TreeMap<>();
        for (String conceptPath : conceptPaths) {
            Map<Double, Integer> countMap = new LinkedHashMap<>();
            if (store.getBlock(conceptPath).orElse(null) instanceof NumericLayoutBlock block) {
                DoubleBuffer values = block.values();
                IntBuffer patients = block.patients();
                int size = block.size();
                int i = 0;
                while (i < size) {
                    double value = values.get(i);
                    int count = 0;
                    int j = i;
                    while (j < size && Double.compare(values.get(j), value) == 0) {
                        if (cohort.contains(patients.get(j))) {
                            count++;
                        }
                        j++;
                    }
                    if (count > 0) {
                        countMap.put(value, count);
                    }
                    i = j;
                }
            }
            conceptMap.put(conceptPath, countMap);
        }
        return conceptMap;
    }

    /**
     * For each {@code select} path the store holds, the number of its observations whose patient is in the cohort; -1 when its block cannot
     * be loaded. Unknown paths are omitted. Computed with a plain loop, without the javabin processor's unsynchronized parallel writes into
     * a {@code TreeMap}.
     *
     * @param query the query
     * @return path to count, in Java string order
     */
    @Override
    public Map<String, Integer> runObservationCrossCounts(Query query) {
        RoaringBitmap cohort = cohort(query);
        Map<String, Integer> counts = new TreeMap<>();
        for (String concept : query.select()) {
            try {
                store.getBlock(concept).ifPresent(block -> counts.put(concept, (int) block.observationsIn(cohort)));
            } catch (RuntimeException e) {
                counts.put(concept, -1);
            }
        }
        return counts;
    }
}
