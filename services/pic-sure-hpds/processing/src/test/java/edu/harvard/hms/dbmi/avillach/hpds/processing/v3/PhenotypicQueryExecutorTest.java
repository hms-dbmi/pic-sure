package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

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
    private PhenotypicObservationStore phenotypicObservationStore;

    private PhenotypicQueryExecutor phenotypicQueryExecutor;

    @BeforeEach
    public void setup() {
        phenotypicQueryExecutor = new PhenotypicQueryExecutor(phenotypeMetaStore, phenotypicObservationStore, 0);
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

        Set<Integer> patientIds = Set.of(2, 3, 5);
        when(phenotypicObservationStore.getKeysForRange(conceptPath, 35.0, 45.0)).thenReturn(patientIds);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(patientIds, patientSet);
    }

    @Test
    public void getPatientSet_validCategoricalFilter_returnPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, Set.of("Finnish"), null, null, null), null,
            ResultType.COUNT, null, null
        );

        Set<Integer> patientIds = Set.of(2, 3, 5, 8, 13);
        when(phenotypicObservationStore.getKeysForValues(conceptPath, Set.of("Finnish"))).thenReturn(patientIds);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(patientIds, patientSet);
    }

    @Test
    public void getPatientSet_nonExistentCategoricalFilter_returnNoPatients() {
        String conceptPath = "\\open_access-1000Genomes\\data\\NOT_A_CONCEPT_PATH\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, Set.of("Finnish"), null, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenotypicObservationStore.getKeysForValues(conceptPath, Set.of("Finnish"))).thenReturn(Set.of());

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(Set.of(), patientSet);
    }

    @Test
    public void getPatientSet_nonExistentNumericFilter_returnNoPatients() {
        String conceptPath = "\\open_access-1000Genomes\\data\\NOT_A_CONCEPT_PATH\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, null, 42.0, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenotypicObservationStore.getKeysForRange(conceptPath, 42.0, null)).thenReturn(Set.of());

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
            new PhenotypicFilter(PhenotypicFilterType.FILTER, categoricalConcept1, Set.of("Finnish"), null, null, null);
        PhenotypicFilter numericFilter1 = new PhenotypicFilter(PhenotypicFilterType.FILTER, numericConcept1, null, 42.0, null, null);
        PhenotypicFilter categoricalFilter2 =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, categoricalConcept2, Set.of("female"), null, null, null);
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

        when(phenotypicObservationStore.getKeysForValues(categoricalConcept1, Set.of("Finnish"))).thenReturn(catFilter1Ids);
        when(phenotypicObservationStore.getKeysForValues(categoricalConcept2, Set.of("female"))).thenReturn(catFilter2Ids);
        when(phenotypicObservationStore.getKeysForRange(numericConcept1, 42.0, null)).thenReturn(numFilter1Ids);
        when(phenotypicObservationStore.getKeysForRange(numericConcept2, null, 175.5)).thenReturn(numFilter2Ids);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(expectedPatients, patientSet);
    }

    @Test
    public void getPatientSet_validCategoricalFilterMultipleValues_returnPatients() throws ExecutionException {
        String conceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, Set.of("Finnish", "Zapotec"), null, null, null), null,
            ResultType.COUNT, null, null
        );

        Set<Integer> patientIds = Set.of(8, 13, 21);
        when(phenotypicObservationStore.getKeysForValues(conceptPath, Set.of("Finnish", "Zapotec"))).thenReturn(patientIds);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(patientIds, patientSet);
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

        when(phenotypeMetaStore.getChildConceptPaths("\\open_access-1000Genomes\\"))
            .thenReturn(Set.of(categoricalConceptPath, numericConceptPath));
        List<Integer> numericPatientIds = List.of(2, 3, 5);
        List<Integer> categoricalPatientIds = List.of(10, 100, 1000, 100000);

        when(phenotypicObservationStore.getAllKeys(categoricalConceptPath)).thenReturn(categoricalPatientIds);
        when(phenotypicObservationStore.getAllKeys(numericConceptPath)).thenReturn(numericPatientIds);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        Set<Integer> expectedPatients = new HashSet<>();
        expectedPatients.addAll(categoricalPatientIds);
        expectedPatients.addAll(numericPatientIds);
        assertEquals(expectedPatients, patientSet);

        verify(phenotypicObservationStore, times(0)).getAllKeys(nonMatchingConceptPath);
    }

    @Test
    public void getPatientSet_anyRecordOfFilterNoMatches_returnNoPatients() {
        String nonMatchingConceptPath = "\\synthea\\data\\SYNTHETIC_AGE\\";
        Query query = new Query(
            List.of(), List.of(),
            new PhenotypicFilter(PhenotypicFilterType.ANY_RECORD_OF, "\\open_access-1000Genomes\\", null, null, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenotypeMetaStore.getChildConceptPaths("\\open_access-1000Genomes\\")).thenReturn(Set.of());

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

        List<Integer> keyList = List.of(2, 3, 5, 8, 13, 13, 8, 5);
        when(phenotypicObservationStore.getAllKeys(conceptPath)).thenReturn(keyList);

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(new HashSet<>(keyList), patientSet);
    }

    @Test
    public void getPatientSet_notFoundRequiredFilter_returnNoPatients() {
        String conceptPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        Query query = new Query(
            List.of(), List.of(), new PhenotypicFilter(PhenotypicFilterType.REQUIRED, conceptPath, null, null, null, null), null,
            ResultType.COUNT, null, null
        );

        when(phenotypicObservationStore.getAllKeys(conceptPath)).thenReturn(List.of());

        Set<Integer> patientSet = phenotypicQueryExecutor.getPatientSet(query);
        assertEquals(Set.of(), patientSet);
    }

    private static final String SEX = "\\demographics\\SEX\\";
    private static final String AGE = "\\demographics\\AGE\\";
    private static final String DIAGNOSIS = "\\diagnosis\\D\\";
    private static final String CONSENTS = "\\_consents\\";

    private PhenotypicQueryExecutor withMinimumClauseSize(int minimum) {
        return new PhenotypicQueryExecutor(phenotypeMetaStore, phenotypicObservationStore, minimum);
    }

    private static PhenotypicFilter valueFilter(String conceptPath, String value) {
        return new PhenotypicFilter(PhenotypicFilterType.FILTER, conceptPath, Set.of(value), null, null, null);
    }

    private static Query query(List<AuthorizationFilter> authorizationFilters, PhenotypicClause clause) {
        return new Query(List.of(), authorizationFilters, clause, List.of(), ResultType.COUNT, null, null);
    }

    @Test
    public void getPatientSet_minimumClauseSize_filterBelowMinimumMatchesNobody() {
        when(phenotypicObservationStore.getKeysForValues(SEX, Set.of("female"))).thenReturn(Set.of(1, 2));

        assertEquals(Set.of(), withMinimumClauseSize(3).getPatientSet(query(List.of(), valueFilter(SEX, "female"))));
    }

    @Test
    public void getPatientSet_minimumClauseSize_filterAtMinimumIsKept() {
        when(phenotypicObservationStore.getKeysForValues(SEX, Set.of("female"))).thenReturn(Set.of(1, 2, 3));

        assertEquals(Set.of(1, 2, 3), withMinimumClauseSize(3).getPatientSet(query(List.of(), valueFilter(SEX, "female"))));
    }

    @Test
    public void getPatientSet_minimumClauseSize_isolatingSubqueryIsDroppedFromAnOr() {
        // P OR (target AND X): the AND isolates patient 5, so it matches nobody and the result is P alone
        PhenotypicClause isolating =
            new PhenotypicSubquery(null, List.of(valueFilter(AGE, "47"), valueFilter(DIAGNOSIS, "yes")), Operator.AND);
        PhenotypicClause clause = new PhenotypicSubquery(null, List.of(valueFilter(SEX, "female"), isolating), Operator.OR);
        when(phenotypicObservationStore.getKeysForValues(SEX, Set.of("female"))).thenReturn(Set.of(1, 2, 3, 4));
        when(phenotypicObservationStore.getKeysForValues(AGE, Set.of("47"))).thenReturn(Set.of(5, 6, 7));
        when(phenotypicObservationStore.getKeysForValues(DIAGNOSIS, Set.of("yes"))).thenReturn(Set.of(5, 20, 21));

        assertEquals(Set.of(1, 2, 3, 4), withMinimumClauseSize(3).getPatientSet(query(List.of(), clause)));
        assertEquals(Set.of(1, 2, 3, 4, 5), withMinimumClauseSize(0).getPatientSet(query(List.of(), clause)));
    }

    @Test
    public void getPatientSet_minimumClauseSize_smallLeafIsDroppedEvenWhenItsParentIsLarge() {
        PhenotypicClause clause = new PhenotypicSubquery(null, List.of(valueFilter(SEX, "female"), valueFilter(AGE, "47")), Operator.OR);
        when(phenotypicObservationStore.getKeysForValues(SEX, Set.of("female"))).thenReturn(Set.of(1, 2));
        when(phenotypicObservationStore.getKeysForValues(AGE, Set.of("47"))).thenReturn(Set.of(3, 4, 5));

        assertEquals(Set.of(3, 4, 5), withMinimumClauseSize(3).getPatientSet(query(List.of(), clause)));
    }

    @Test
    public void getPatientSet_minimumClauseSize_wholeQueryBelowMinimumMatchesNobody() {
        PhenotypicClause clause = new PhenotypicSubquery(null, List.of(valueFilter(SEX, "female"), valueFilter(AGE, "47")), Operator.AND);
        when(phenotypicObservationStore.getKeysForValues(SEX, Set.of("female"))).thenReturn(Set.of(1, 2, 3, 4));
        when(phenotypicObservationStore.getKeysForValues(AGE, Set.of("47"))).thenReturn(Set.of(3, 4, 5));

        assertEquals(Set.of(), withMinimumClauseSize(3).getPatientSet(query(List.of(), clause)));
    }

    @Test
    public void getPatientSet_minimumClauseSize_clausesAreMeasuredWithinAuthorizedPatients() {
        // The filter matches 5 patients overall but only 2 the caller is authorized for
        when(phenotypicObservationStore.getKeysForValues(CONSENTS, Set.of("phs1"))).thenReturn(Set.of(1, 2, 9, 10));
        when(phenotypicObservationStore.getKeysForValues(SEX, Set.of("female"))).thenReturn(Set.of(1, 2, 3, 4, 5));

        Query query = query(List.of(new AuthorizationFilter(CONSENTS, Set.of("phs1"))), valueFilter(SEX, "female"));
        assertEquals(Set.of(), withMinimumClauseSize(3).getPatientSet(query));
        assertEquals(Set.of(1, 2), withMinimumClauseSize(0).getPatientSet(query));
    }

    @Test
    public void getPatientSet_minimumClauseSize_authorizationFiltersAreNotSubjectToIt() {
        when(phenotypicObservationStore.getKeysForValues(CONSENTS, Set.of("phs1"))).thenReturn(Set.of(1, 2));

        assertEquals(Set.of(1, 2), withMinimumClauseSize(3).getPatientSet(query(List.of(new AuthorizationFilter(CONSENTS, Set.of("phs1"))), null)));
    }

    @Test
    public void getPatientSet_minimumClauseSize_noFiltersReturnsAllPatients() {
        when(phenotypeMetaStore.getPatientIds()).thenReturn(new TreeSet<>(Set.of(1, 2)));

        assertEquals(Set.of(1, 2), withMinimumClauseSize(3).getPatientSet(query(List.of(), null)));
    }
}
