package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PatientAndConceptCount;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Note: This class was copied from {@link edu.harvard.hms.dbmi.avillach.hpds.processing.CountProcessor} and updated to use new Query entity
 */
@Component
public class CountV3Processor implements HpdsV3Processor {

    private final static Logger log = LoggerFactory.getLogger(CountV3Processor.class);

    private final QueryExecutor queryExecutor;

    private final PhenotypicObservationStore phenotypicObservationStore;

    @Autowired
    public CountV3Processor(QueryExecutor queryExecutor, PhenotypicObservationStore phenotypicObservationStore) {
        this.queryExecutor = queryExecutor;
        this.phenotypicObservationStore = phenotypicObservationStore;
    }

    /**
     * Count processor always returns same headers
     */
    @Override
    public String[] getHeaderRow(Query query) {
        return new String[] {"Patient ID", "Count"};
    }

    /**
     * Retrieves a list of patient ids that are valid for the query result and returns the size of that list.
     * 
     * @param query
     * @return
     */
    public int runCounts(Query query) {
        return queryExecutor.getPatientSubsetForQuery(query).size();
    }


    /**
     * Returns a separate observation count for each field in query.crossCountFields when that field is added as a requiredFields entry for
     * the base query.
     * 
     * @param query
     * @return
     */
    public Map<String, Integer> runObservationCrossCounts(Query query) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        Set<Integer> baseQueryPatientSet = queryExecutor.getPatientSubsetForQuery(query);
        query.select().parallelStream().forEach((String concept) -> {
            try {
                phenotypicObservationStore.getCube(concept).ifPresent(cube -> {
                    int observationCount = (int) Arrays.stream(cube.sortedByKey()).filter(keyAndValue -> {
                        return baseQueryPatientSet.contains(keyAndValue.getKey());
                    }).count();
                    counts.put(concept, observationCount);
                });
            } catch (Exception e) {
                counts.put(concept, -1);
            }
        });
        return counts;
    }

    /**
     * Returns a separate count for each field in query.crossCountFields when that field is added as a requiredFields entry for the base
     * query.
     * 
     * @param query
     * @return
     */
    public Map<String, Integer> runCrossCounts(Query query) {
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        Set<Integer> baseQueryPatientSet = queryExecutor.getPatientSubsetForQuery(query);
        query.select().parallelStream().forEach((String concept) -> {
            try {
                Query safeCopy = new Query(
                    List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, concept, null, null, null, null), List.of(),
                    null, null, null
                );
                int matchingPatients = Sets.intersection(queryExecutor.getPatientSubsetForQuery(safeCopy), baseQueryPatientSet).size();
                counts.put(concept, matchingPatients);
            } catch (Exception e) {
                counts.put(concept, -1);
            }
        });
        return counts;
    }

    /**
     * Returns a separate count for each field in the requiredFields and categoryFilters query.
     * <p>
     * The v3 query lets a user add multiple filters on the same variable (e.g. sex=male OR sex=female). Filters are grouped by concept path
     * so each variable produces exactly one entry rather than later filters overwriting earlier ones.
     * <p>
     * A cross count reports each concept's distribution across the cohort. A category is included when it has members in the cohort, OR when
     * it was explicitly named ("called out") by a value filter. This means a value filter never hides cohort members that arrived via an OR
     * branch on another concept (sex=male OR age-required still shows females that have an age), while a value the user specifically asked
     * for is always shown even when its cohort count is zero. Categories that are neither called out nor present in the cohort are omitted,
     * so AND-narrowed concepts do not sprout empty bars.
     *
     * @param query
     * @return a map of categorical data and their counts
     */
    public Map<String, Map<String, Integer>> runCategoryCrossCounts(Query query) {
        Set<Integer> baseQueryPatientSet = queryExecutor.getPatientSubsetForQuery(query);

        Map<String, List<PhenotypicFilter>> filtersByConcept = query.allFilters().stream()
            .filter(this::isCategoryCrossCountFilter).collect(Collectors.groupingBy(PhenotypicFilter::conceptPath));

        Map<String, Map<String, Integer>> categoryCounts = new TreeMap<>();
        for (Map.Entry<String, List<PhenotypicFilter>> entry : filtersByConcept.entrySet()) {
            String conceptPath = entry.getKey();

            TreeMap<String, TreeSet<Integer>> categoryMap = (TreeMap<String, TreeSet<Integer>>) phenotypicObservationStore
                .getCube(conceptPath).map(PhenoCube::getCategoryMap).orElseGet(TreeMap::new);

            Set<String> calledOutValues = entry.getValue().stream().filter(PhenotypicFilter::isCategoricalFilter)
                .flatMap(filter -> filter.values().stream()).collect(Collectors.toSet());

            Map<String, Integer> varCount = new TreeMap<>();
            categoryMap.forEach((String category, TreeSet<Integer> patientSet) -> {
                int count = Sets.intersection(patientSet, baseQueryPatientSet).size();
                if (count > 0 || calledOutValues.contains(category)) {
                    varCount.put(category, count);
                }
            });
            categoryCounts.put(conceptPath, varCount);
        }
        return categoryCounts;
    }

    /**
     * A categorical cross count is produced for value filters and for REQUIRED filters on categorical concepts, excluding variant-spec
     * paths. A REQUIRED filter on a continuous concept is gated out here (it is handled by {@link #runContinuousCrossCounts}); otherwise it
     * would be admitted and rely on the cube having no category map to avoid emitting a meaningless empty entry.
     */
    private boolean isCategoryCrossCountFilter(PhenotypicFilter filter) {
        if (VariantUtils.pathIsVariantSpec(filter.conceptPath())) {
            return false;
        }
        if (filter.isCategoricalFilter()) {
            return true;
        }
        return PhenotypicFilterType.REQUIRED.equals(filter.phenotypicFilterType())
            && Optional.ofNullable(queryExecutor.getDictionary().get(filter.conceptPath())).map(meta -> meta.isCategorical()).orElse(false);
    }

    /**
     * Returns the distribution of observed values for each continuous concept in the query.
     * <p>
     * A continuous concept reports the distribution of every observed value across the cohort. Each filter's min/max only constrains the
     * cohort (handled by the query executor); it does not limit which values are shown. So a range filter never hides cohort members that
     * arrived via an OR branch on another concept (age&gt;50 OR sex=male still shows the younger ages of the OR'd males), and multiple range
     * filters on the same concept collapse to one entry since every patient is counted once from the cube.
     *
     * @param query
     * @return a map of numerical data and their counts
     */
    public Map<String, Map<Double, Integer>> runContinuousCrossCounts(Query query) {
        Set<Integer> baseQueryPatientSet = queryExecutor.getPatientSubsetForQuery(query);

        Set<String> conceptPaths = query.allFilters().stream().filter(this::isContinuousCrossCountFilter)
            .map(PhenotypicFilter::conceptPath).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Map<Double, Integer>> conceptMap = new TreeMap<>();
        for (String conceptPath : conceptPaths) {
            KeyAndValue<Double>[] pairs = phenotypicObservationStore.getCube(conceptPath)
                .map(phenoCube -> ((PhenoCube<Double>) phenoCube).getEntriesForValueRange(null, null))
                .orElseGet(() -> new KeyAndValue[] {});
            Map<Double, Integer> countMap = new TreeMap<>();
            for (KeyAndValue<Double> patientConceptPair : pairs) {
                if (baseQueryPatientSet.contains(patientConceptPair.getKey())) {
                    countMap.merge((double) patientConceptPair.getValue(), 1, Integer::sum);
                }
            }
            conceptMap.put(conceptPath, countMap);
        }
        return conceptMap;
    }

    private boolean isContinuousCrossCountFilter(PhenotypicFilter phenotypicFilter) {
        return Optional.ofNullable(queryExecutor.getDictionary().get(phenotypicFilter.conceptPath()))
            .map(meta -> !meta.isCategorical())
            .orElse(false);
    }

    /**
     * Until we have a count based query that takes longer than 30 seconds to run, we should discourage running them asynchronously in the
     * backend as this results in unnecessary request-response cycles.
     */
    @Override
    public void runQuery(Query query, AsyncResult asyncResult) {
        throw new UnsupportedOperationException("Counts do not run asynchronously.");
    }

    /**
     * Process only variantInfoFilters to count the number of variants that would be included in evaluating the query.
     * 
     * This does not actually evaluate a patient set for the query.
     * 
     * @param query
     * @return the number of variants that would be used to filter patients if the incomingQuery was run as a COUNT query.
     */
    public Map<String, Object> runVariantCount(Query query) {
        TreeMap<String, Object> response = new TreeMap<String, Object>();
        // if(!query.getVariantInfoFilters().isEmpty()) {
        if (!query.genomicFilters().isEmpty()) {
            response.put("count", queryExecutor.getVariantList(query).size());
            response.put("message", "Query ran successfully");
        } else {
            response.put("count", "0");
            response.put("message", "No variant filters were supplied, so no query was run.");
        }
        return response;
    }

    public PatientAndConceptCount runPatientAndConceptCount(Query incomingQuery) {
        log.info("Starting Patient and Concept Count query {}", incomingQuery.picsureId());
        log.info("Calculating available concepts");
        long concepts = incomingQuery.select().stream().map(phenotypicObservationStore::getCube).filter(Optional::isPresent).count();
        log.info("Calculating patient counts");
        int patients = runCounts(incomingQuery);
        PatientAndConceptCount patientAndConceptCount = new PatientAndConceptCount();
        patientAndConceptCount.setConceptCount(concepts);
        patientAndConceptCount.setPatientCount(patients);
        log.info("Completed Patient and Concept Count query {}", incomingQuery.picsureId());
        return patientAndConceptCount;
    }
}
