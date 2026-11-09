package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiValueQueryV3ProcessorSampleTest {

    private static final Set<Integer> COHORT = Set.of(1, 2, 3, 4, 5, 6, 7, 8);

    @Mock
    private QueryExecutor queryExecutor;

    @Mock
    private PhenotypicObservationStore phenotypicObservationStore;

    @Mock
    private CohortSampler cohortSampler;

    @Mock
    private AsyncResult result;

    @Captor
    private ArgumentCaptor<List<List<List<String>>>> rows;

    private MultiValueQueryV3Processor processor;

    @BeforeEach
    void setup() {
        processor = new MultiValueQueryV3Processor(queryExecutor, phenotypicObservationStore, cohortSampler, 3);
    }

    @Test
    void sampleQueryExportsOnlySampledIds() {
        Query query = query(true);
        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(COHORT);
        when(cohortSampler.sample(query, COHORT)).thenReturn(new TreeSet<>(Set.of(2, 5)));

        processor.runQuery(query, result);

        assertEquals(Set.of("2", "5"), exportedIds());
    }

    @Test
    void nonSampleQueryExportsTheWholeCohort() {
        Query query = query(false);
        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(COHORT);

        processor.runQuery(query, result);

        assertEquals(COHORT.stream().map(String::valueOf).collect(Collectors.toSet()), exportedIds());
        verifyNoInteractions(cohortSampler);
    }

    private Set<String> exportedIds() {
        verify(result, atLeastOnce()).appendMultiValueResults(rows.capture());
        return rows.getAllValues().stream().flatMap(List::stream).map(row -> row.get(0).get(0)).collect(Collectors.toSet());
    }

    private static Query query(boolean sample) {
        return new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME, null, null, sample);
    }
}
