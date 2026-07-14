package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Operator;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountV3ProcessorTest {

    private CountV3Processor countV3Processor;

    @Mock
    private QueryExecutor queryExecutor;

    @Mock
    private PhenotypicObservationStore phenotypicObservationStore;

    @BeforeEach
    public void setup() {
        countV3Processor = new CountV3Processor(queryExecutor, phenotypicObservationStore);
    }


    @Test
    public void runCrossCounts_validPaths_returnPatientCounts() {
        Set<Integer> allPaientIds = Set.of(2, 3, 5, 8, 13, 21);
        Set<Integer> patientSubset1 = Set.of(1, 2, 3, 5, 8);
        Set<Integer> patientSubset2 = Set.of(8, 13, 21, 34);
        String conceptPath1 = "\\_studies_consents\\phs001194\\HMB\\";
        String conceptPath2 = "\\_studies_consents\\phs000007\\HMB-IRB-MDS\\";

        Query fullQuery = new Query(List.of(conceptPath1, conceptPath2), List.of(), null, List.of(), ResultType.CROSS_COUNT, null, null);

        Query queryConcept1 = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath1, null, null, null, null), List.of(),
            null, null, null
        );
        Query queryConcept2 = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath2, null, null, null, null), List.of(),
            null, null, null
        );

        when(queryExecutor.getPatientSubsetForQuery(fullQuery)).thenReturn(allPaientIds);
        when(queryExecutor.getPatientSubsetForQuery(queryConcept1)).thenReturn(patientSubset1);
        when(queryExecutor.getPatientSubsetForQuery(queryConcept2)).thenReturn(patientSubset2);

        Map<String, Integer> crossCountsMap = countV3Processor.runCrossCounts(fullQuery);
        assertEquals(2, crossCountsMap.size());
        assertEquals(Set.of(conceptPath1, conceptPath2), crossCountsMap.keySet());
        // Should not include 1 as it is not in the base patient set
        assertEquals(4, crossCountsMap.get(conceptPath1));
        // should not include 34 as it is not in the base patient set
        assertEquals(3, crossCountsMap.get(conceptPath2));
    }

    @Test
    public void runCrossCounts_validPathsNoMatchingPatients_returnZeros() {
        Set<Integer> allPaientIds = Set.of(1000, 1001, 1002, 1003);
        Set<Integer> patientSubset1 = Set.of(1, 2, 3, 5, 8);
        Set<Integer> patientSubset2 = Set.of(8, 13, 21, 34);
        String conceptPath1 = "\\_studies_consents\\phs001194\\HMB\\";
        String conceptPath2 = "\\_studies_consents\\phs000007\\HMB-IRB-MDS\\";

        Query fullQuery = new Query(List.of(conceptPath1, conceptPath2), List.of(), null, List.of(), ResultType.CROSS_COUNT, null, null);

        Query queryConcept1 = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath1, null, null, null, null), List.of(),
            null, null, null
        );
        Query queryConcept2 = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath2, null, null, null, null), List.of(),
            null, null, null
        );

        when(queryExecutor.getPatientSubsetForQuery(fullQuery)).thenReturn(allPaientIds);
        when(queryExecutor.getPatientSubsetForQuery(queryConcept1)).thenReturn(patientSubset1);
        when(queryExecutor.getPatientSubsetForQuery(queryConcept2)).thenReturn(patientSubset2);

        Map<String, Integer> crossCountsMap = countV3Processor.runCrossCounts(fullQuery);
        assertEquals(2, crossCountsMap.size());
        assertEquals(Set.of(conceptPath1, conceptPath2), crossCountsMap.keySet());
        // Should not include 1 as it is not in the base patient set
        assertEquals(0, crossCountsMap.get(conceptPath1));
        // should not include 34 as it is not in the base patient set
        assertEquals(0, crossCountsMap.get(conceptPath2));
    }


    @Test
    public void runCrossCounts_invalidPath_returnNegativeOne() {
        Set<Integer> allPaientIds = Set.of(2, 3, 5, 8, 13, 21);
        Set<Integer> patientSubset1 = Set.of(1, 2, 3, 5, 8);
        Set<Integer> patientSubset2 = Set.of(8, 13, 21, 34);
        String conceptPath1 = "\\_studies_consents\\phs001194\\HMB\\";
        String conceptPath2 = "\\_studies_consents\\phs000007\\HMB-IRB-MDS\\";

        Query fullQuery = new Query(List.of(conceptPath1, conceptPath2), List.of(), null, List.of(), ResultType.CROSS_COUNT, null, null);

        Query queryConcept1 = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath1, null, null, null, null), List.of(),
            null, null, null
        );
        Query queryConcept2 = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath2, null, null, null, null), List.of(),
            null, null, null
        );

        when(queryExecutor.getPatientSubsetForQuery(fullQuery)).thenReturn(allPaientIds);
        when(queryExecutor.getPatientSubsetForQuery(queryConcept1)).thenReturn(patientSubset1);
        when(queryExecutor.getPatientSubsetForQuery(queryConcept2)).thenThrow(RuntimeException.class);

        Map<String, Integer> crossCountsMap = countV3Processor.runCrossCounts(fullQuery);
        assertEquals(2, crossCountsMap.size());
        assertEquals(Set.of(conceptPath1, conceptPath2), crossCountsMap.keySet());
        // Should not include 1 as it is not in the base patient set
        assertEquals(4, crossCountsMap.get(conceptPath1));
        // should not include 34 as it is not in the base patient set
        assertEquals(-1, crossCountsMap.get(conceptPath2));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void runContinuousCrossCounts_requiredNumericConcept_returnsObservedValues() {
        String conceptPath = "\\demographics\\AGE\\";
        PhenotypicFilter required = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath, null, null, null, null);
        Query query = new Query(List.of(), List.of(), required, List.of(), ResultType.CONTINUOUS_CROSS_COUNT, null, null);

        PhenoCube<Double> cube = new PhenoCube<>(conceptPath, Double.class);
        cube.setSortedByKey(
            new KeyAndValue[] {new KeyAndValue<>(1, 18.0), new KeyAndValue<>(2, 19.0), new KeyAndValue<>(3, 19.0)}
        );

        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(Set.of(1, 3));
        when(queryExecutor.getDictionary()).thenReturn(Map.of(conceptPath, new ColumnMeta().setName(conceptPath).setCategorical(false)));
        when(phenotypicObservationStore.getCube(conceptPath)).thenReturn(java.util.Optional.of(cube));

        Map<String, Map<Double, Integer>> crossCounts = countV3Processor.runContinuousCrossCounts(query);

        assertEquals(1, crossCounts.get(conceptPath).get(18.0));
        assertEquals(1, crossCounts.get(conceptPath).get(19.0));
    }

    @Test
    public void runCategoryCrossCounts_duplicateValueFiltersSamePath_mergesValues() {
        String sexPath = "\\demographics\\SEX\\";
        PhenotypicFilter maleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("male"), null, null, null);
        PhenotypicFilter femaleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("female"), null, null, null);
        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(maleFilter, femaleFilter), Operator.OR);
        Query query = new Query(List.of(), List.of(), subquery, List.of(), ResultType.CATEGORICAL_CROSS_COUNT, null, null);

        TreeMap<String, TreeSet<Integer>> categoryMap = new TreeMap<>();
        categoryMap.put("male", new TreeSet<>(Set.of(1, 2, 3)));
        categoryMap.put("female", new TreeSet<>(Set.of(4, 5)));
        PhenoCube<String> cube = new PhenoCube<>(sexPath, String.class);
        cube.setCategoryMap(categoryMap);

        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(Set.of(1, 2, 3, 4, 5));
        when(phenotypicObservationStore.getCube(sexPath)).thenReturn(Optional.of(cube));

        Map<String, Map<String, Integer>> crossCounts = countV3Processor.runCategoryCrossCounts(query);

        // Both filter values land in a single SEX entry instead of one overwriting the other
        assertEquals(1, crossCounts.size());
        assertEquals(Set.of("male", "female"), crossCounts.get(sexPath).keySet());
        assertEquals(3, crossCounts.get(sexPath).get("male"));
        assertEquals(2, crossCounts.get(sexPath).get("female"));
    }

    @Test
    public void runCategoryCrossCounts_requiredFilterPartialBaseSet_countsEachPatientOnce() {
        String sexPath = "\\demographics\\SEX\\";
        PhenotypicFilter requiredSex = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, sexPath, null, null, null, null);
        Query query = new Query(List.of(), List.of(), requiredSex, List.of(), ResultType.CATEGORICAL_CROSS_COUNT, null, null);

        TreeMap<String, TreeSet<Integer>> categoryMap = new TreeMap<>();
        categoryMap.put("male", new TreeSet<>(Set.of(1, 2, 3)));
        categoryMap.put("female", new TreeSet<>(Set.of(4, 5)));
        PhenoCube<String> cube = new PhenoCube<>(sexPath, String.class);
        cube.setCategoryMap(categoryMap);

        // Base set is a partial subset of every category, forcing the per-patient counting path.
        // Only patient 1 (male) and patient 4 (female) are in the cohort, so each category count must be exactly 1.
        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(Set.of(1, 4));
        when(queryExecutor.getDictionary()).thenReturn(Map.of(sexPath, new ColumnMeta().setName(sexPath).setCategorical(true)));
        when(phenotypicObservationStore.getCube(sexPath)).thenReturn(Optional.of(cube));

        Map<String, Map<String, Integer>> crossCounts = countV3Processor.runCategoryCrossCounts(query);

        assertEquals(1, crossCounts.get(sexPath).get("male"));
        assertEquals(1, crossCounts.get(sexPath).get("female"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void runContinuousCrossCounts_showsCohortValuesOutsideFilterRange() {
        String agePath = "\\demographics\\AGE\\";
        // A range filter (age 10-20); the cohort, however, includes patient 2 (age 40) who is outside that range -- as happens when the
        // range is OR'd with a filter on another concept. The age distribution must include 40 because patient 2 is in the cohort; the
        // filter range only constrains the cohort, not which values are displayed.
        PhenotypicFilter youngFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, agePath, null, 10.0, 20.0, null);
        Query query = new Query(List.of(), List.of(), youngFilter, List.of(), ResultType.CONTINUOUS_CROSS_COUNT, null, null);

        PhenoCube<Double> cube = new PhenoCube<>(agePath, Double.class);
        cube.setSortedByKey(new KeyAndValue[] {new KeyAndValue<>(1, 15.0), new KeyAndValue<>(2, 40.0), new KeyAndValue<>(3, 65.0)});

        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(Set.of(1, 2));
        when(queryExecutor.getDictionary()).thenReturn(Map.of(agePath, new ColumnMeta().setName(agePath).setCategorical(false)));
        when(phenotypicObservationStore.getCube(agePath)).thenReturn(Optional.of(cube));

        Map<String, Map<Double, Integer>> crossCounts = countV3Processor.runContinuousCrossCounts(query);

        assertEquals(1, crossCounts.get(agePath).get(15.0));
        assertEquals(1, crossCounts.get(agePath).get(40.0));
        // patient 3 (65) is not in the cohort, so it is absent
        assertNull(crossCounts.get(agePath).get(65.0));
    }
}
