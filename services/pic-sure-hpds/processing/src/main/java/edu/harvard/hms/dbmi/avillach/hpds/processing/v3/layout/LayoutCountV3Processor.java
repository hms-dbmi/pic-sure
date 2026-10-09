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
 * <p>The per-concept work of every cross count runs on a parallel stream, as the Rust engine runs it with {@code par_iter}. Results are
 * gathered in a fixed order afterwards, so the output does not depend on scheduling.
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
        List<String> concepts = query.select();
        List<Integer> perConcept = concepts.parallelStream().map(concept -> crossCount(concept, cohort)).toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < concepts.size(); i++) {
            counts.putIfAbsent(concepts.get(i), perConcept.get(i));
        }
        return counts;
    }

    private int crossCount(String concept, RoaringBitmap cohort) {
        try {
            return RoaringBitmap.andCardinality(store.getAllKeys(concept), cohort);
        } catch (RuntimeException e) {
            return -1;
        }
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

        return filtersByConcept.entrySet().parallelStream().collect(
            Collectors
                .toMap(Map.Entry::getKey, entry -> categoryCounts(entry.getKey(), entry.getValue(), cohort), (a, b) -> a, TreeMap::new)
        );
    }

    private Map<String, Integer> categoryCounts(String conceptPath, List<PhenotypicFilter> filters, RoaringBitmap cohort) {
        Set<String> calledOutValues = filters.stream().filter(PhenotypicFilter::isCategoricalFilter)
            .flatMap(filter -> filter.values().stream()).collect(Collectors.toSet());
        Map<String, Integer> varCount = new TreeMap<>();
        if (store.getBlock(conceptPath).orElse(null) instanceof CategoricalLayoutBlock block) {
            for (int i = 0; i < block.dictionarySize(); i++) {
                int count = RoaringBitmap.andCardinality(block.bitmap(i), cohort);
                String category = block.value(i);
                if (count > 0 || calledOutValues.contains(category)) {
                    varCount.put(category, count);
                }
            }
        }
        return varCount;
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

        return conceptPaths.parallelStream()
            .collect(Collectors.toMap(path -> path, path -> continuousCounts(path, cohort), (a, b) -> a, TreeMap::new));
    }

    private Map<Double, Integer> continuousCounts(String conceptPath, RoaringBitmap cohort) {
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
        return countMap;
    }

    /**
     * For each {@code select} path the store holds, the number of its observations whose patient is in the cohort; -1 when its block cannot
     * be loaded. Unknown paths are omitted. Each concept is counted on a parallel stream and the results are gathered into a
     * {@link TreeMap} afterwards, without the javabin processor's unsynchronized parallel writes into one.
     *
     * @param query the query
     * @return path to count, in Java string order
     */
    @Override
    public Map<String, Integer> runObservationCrossCounts(Query query) {
        RoaringBitmap cohort = cohort(query);
        return query.select().parallelStream().filter(concept -> store.concept(concept).isPresent())
            .collect(Collectors.toMap(concept -> concept, concept -> observationCount(concept, cohort), (a, b) -> a, TreeMap::new));
    }

    private int observationCount(String concept, RoaringBitmap cohort) {
        try {
            return store.getBlock(concept).map(block -> (int) block.observationsIn(cohort)).orElse(-1);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
