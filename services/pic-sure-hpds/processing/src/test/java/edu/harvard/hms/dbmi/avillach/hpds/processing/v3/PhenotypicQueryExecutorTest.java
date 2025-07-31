package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class PhenotypicQueryExecutorTest {

    @Mock
    private PhenotypeMetaStore phenotypeMetaStore;

    @Mock
    private LoadingCache<String, PhenoCube<?>> phenoCubeCache;

    private PhenotypicQueryExecutor phenotypicQueryExecutor;

    @BeforeEach
    public void setup() {
        phenotypicQueryExecutor = new PhenotypicQueryExecutor(phenotypeMetaStore, phenoCubeCache);
    }

    @Test
    public void getPatientSet_noFilters_returnAllPatients() {
        Query query = new Query(List.of(), List.of(), null, null, ResultType.COUNT, null, null);

        Set<Integer> patientIds = Set.of(10, 100, 1000);
        when(phenotypeMetaStore.getPatientIds()).thenReturn(new TreeSet<>(patientIds));

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(patientIds, patientSet);
    }

    @Test
    public void getPatientSet_validNumericFilter_returnPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, null, 35.0, 45.0, null), null,
            ResultType.COUNT, null, null
        );

        PhenoCube mockPhenoCube = mock(PhenoCube.class);
        Set<Integer> patientIds = Set.of(2, 3, 5);
        when(mockPhenoCube.getKeysForRange(35.0, 45.0)).thenReturn(patientIds);
        when(phenoCubeCache.get(conceptPath)).thenReturn(mockPhenoCube);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(patientIds, patientSet);
    }

    @Test
    public void getPatientSet_validCategoricalFilter_returnPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, List.of("Finnish"), null, null, null),
            null, ResultType.COUNT, null, null
        );

        PhenoCube mockPhenoCube = mock(PhenoCube.class);
        Set<Integer> patentIds = Set.of(2, 3, 5, 8, 13);
        when(mockPhenoCube.getKeysForValue("Finnish")).thenReturn(patentIds);
        when(phenoCubeCache.get(conceptPath)).thenReturn(mockPhenoCube);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(patentIds, patientSet);
    }

    @Test
    public void getPatientSet_nonExistentCategoricalFilter_returnNoPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\NOT_A_CONCEPT_PATH\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, List.of("Finnish"), null, null, null),
            null, ResultType.COUNT, null, null
        );

        when(phenoCubeCache.get(conceptPath)).thenThrow(ExecutionException.class);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(Set.of(), patientSet);
    }

    @Test
    public void getPatientSet_nonExistentNumericFilter_returnNoPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\NOT_A_CONCEPT_PATH\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, null, 42.0, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenoCubeCache.get(conceptPath)).thenThrow(ExecutionException.class);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(Set.of(), patientSet);
    }


    @Test
    public void getPatientSet_complexNestedFilters_returnPatients() throws ExecutionException {
        String categoricalConcept1 = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        String categoricalConcept2 = "\\open_access-1000Genomes\\data\\SEX\\";
        String numericConcept1 = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";
        String numericConcept2 = "\\open_access-1000Genomes\\data\\SYNTHETIC_HEIGHT\\";

        PhenotypicFilter categoricalFilter1 =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, categoricalConcept1, List.of("Finnish"), null, null, null);
        PhenotypicFilter numericFilter1 = new PhenotypicFilter(PhenotypicFilterType.FILTER, numericConcept1, null, 42.0, null, null);
        PhenotypicFilter categoricalFilter2 =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, categoricalConcept2, List.of("female"), null, null, null);
        PhenotypicFilter numericFilter2 = new PhenotypicFilter(PhenotypicFilterType.FILTER, numericConcept2, null, null, 175.5, null);
        PhenotypicClause phenotypicSubquery1 = new PhenotypicSubquery(null, List.of(categoricalFilter1, numericFilter1), Operator.AND);
        PhenotypicClause phenotypicSubquery2 = new PhenotypicSubquery(null, List.of(categoricalFilter2, numericFilter2), Operator.AND);
        PhenotypicClause topSubquery = new PhenotypicSubquery(null, List.of(phenotypicSubquery1, phenotypicSubquery2), Operator.OR);

        Query query = new Query(List.of(), List.of(), topSubquery, List.of(), ResultType.COUNT, null, null);

        Set<Integer> catFilter1Ids = Set.of(3, 5, 8, 13, 21);
        Set<Integer> numFilter1Ids = Set.of(2, 3, 5, 8, 13);
        Set<Integer> catFilter2Ids = Set.of(10, 100, 1000);
        Set<Integer> numFilter2Ids = Set.of(999, 1000, 10001);
        // (catFilter1Ids AND numFilter1Ids) OR (catFilter2Ids AND numFilter2Ids)
        Set<Integer> expectedPatients = Set.of(3, 5, 8, 13, 1000);

        PhenoCube catFilter1PhenoCube = mock(PhenoCube.class);
        when(catFilter1PhenoCube.getKeysForValue("Finnish")).thenReturn(catFilter1Ids);
        when(phenoCubeCache.get(categoricalConcept1)).thenReturn(catFilter1PhenoCube);
        PhenoCube catFilter2PhenoCube = mock(PhenoCube.class);
        when(catFilter2PhenoCube.getKeysForValue("female")).thenReturn(catFilter2Ids);
        when(phenoCubeCache.get(categoricalConcept2)).thenReturn(catFilter2PhenoCube);

        PhenoCube numFilter1PhenoCube = mock(PhenoCube.class);
        when(numFilter1PhenoCube.getKeysForRange(42.0, null)).thenReturn(numFilter1Ids);
        when(phenoCubeCache.get(numericConcept1)).thenReturn(numFilter1PhenoCube);
        PhenoCube numFilter2PhenoCube = mock(PhenoCube.class);
        when(numFilter2PhenoCube.getKeysForRange(null, 175.5)).thenReturn(numFilter2Ids);
        when(phenoCubeCache.get(numericConcept2)).thenReturn(numFilter2PhenoCube);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(expectedPatients, patientSet);
    }

    @Test
    public void getPatientSet_validCategoricalFilterMultipleValues_returnPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, List.of("Finnish", "Zapotec"), null, null, null), null,
            ResultType.COUNT, null, null
        );

        PhenoCube mockPhenoCube = mock(PhenoCube.class);
        Set<Integer> patentIds = Set.of(2, 3, 5);
        Set<Integer> patentIds2 = Set.of(8, 13, 21);
        when(mockPhenoCube.getKeysForValue("Finnish")).thenReturn(patentIds);
        when(mockPhenoCube.getKeysForValue("Zapotec")).thenReturn(patentIds2);
        when(phenoCubeCache.get(conceptPath)).thenReturn(mockPhenoCube);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(Sets.union(patentIds, patentIds2), patientSet);
    }

    @Test
    public void getPatientSet_validAnyRecordOfFilter_returnPatients() throws ExecutionException {
        String categoricalConceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        String numericConceptPath = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";
        String nonMatchingConceptPath = "\\synthea\\data\\SYNTHETIC_AGE\\";
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, "\\open_access-1000Genomes\\", null, null, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenotypeMetaStore.getColumnNames()).thenReturn(Set.of(categoricalConceptPath, numericConceptPath, nonMatchingConceptPath));

        PhenoCube numericPhenoCube = mock(PhenoCube.class);
        List<Integer> numericPatientIds = List.of(2, 3, 5);
        when(numericPhenoCube.keyBasedIndex()).thenReturn(numericPatientIds);

        PhenoCube categoricalPhenoCube = mock(PhenoCube.class);
        List<Integer> categoricalPatientIds = List.of(10, 100, 1000, 100000);
        when(categoricalPhenoCube.keyBasedIndex()).thenReturn(categoricalPatientIds);

        when(phenoCubeCache.get(categoricalConceptPath)).thenReturn(categoricalPhenoCube);
        when(phenoCubeCache.get(numericConceptPath)).thenReturn(numericPhenoCube);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        Set<Integer> expectedPatients = new HashSet<>();
        expectedPatients.addAll(categoricalPatientIds);
        expectedPatients.addAll(numericPatientIds);
        assertEquals(expectedPatients, patientSet);

        verify(phenoCubeCache, times(0)).get(nonMatchingConceptPath);
    }

    @Test
    public void getPatientSet_anyRecordOfFilterNoMatches_returnNoPatients() {
        String nonMatchingConceptPath = "\\synthea\\data\\SYNTHETIC_AGE\\";
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, "\\open_access-1000Genomes\\", null, null, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenotypeMetaStore.getColumnNames()).thenReturn(Set.of(nonMatchingConceptPath));

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(Set.of(), patientSet);
    }


    @Test
    public void getPatientSet_validRequiredFilter_returnPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath, null, null, null, null), null,
            ResultType.COUNT, null, null
        );

        PhenoCube mockPhenoCube = mock(PhenoCube.class);
        Set<Integer> patentIds = Set.of(2, 3, 5, 8, 13);
        when(mockPhenoCube.keyBasedIndex()).thenReturn(patentIds.stream().toList());
        when(phenoCubeCache.get(conceptPath)).thenReturn(mockPhenoCube);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(patentIds, patientSet);
    }

    @Test
    public void getPatientSet_notFoundRequiredFilter_returnNoPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath, null, null, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenoCubeCache.get(conceptPath)).thenThrow(ExecutionException.class);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(Set.of(), patientSet);
    }

}
