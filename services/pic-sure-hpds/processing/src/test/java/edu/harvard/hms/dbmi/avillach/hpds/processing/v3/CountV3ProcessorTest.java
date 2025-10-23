package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
}
