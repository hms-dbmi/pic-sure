package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries.TimeSeriesConversionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeseriesV3ProcessorSampleTest {

    private static final String CONCEPT = "\\labs\\hba1c\\";
    private static final Set<Integer> COHORT = Set.of(1, 2, 3, 4, 5, 6, 7, 8);

    @Mock
    private QueryExecutor queryExecutor;

    @Mock
    private PhenotypicObservationStore phenotypicObservationStore;

    @Mock
    private CohortSampler cohortSampler;

    @Mock
    private AsyncResult result;

    @Mock
    private PhenoCube<String> cube;

    private TimeseriesV3Processor processor;

    @BeforeEach
    void setup() {
        processor =
            new TimeseriesV3Processor(queryExecutor, new TimeSeriesConversionService(), phenotypicObservationStore, cohortSampler, 10);
    }

    @Test
    void sampleQueryExportsOnlySampledIds() {
        Query query = query(true);
        Set<Integer> sampled = new TreeSet<>(Set.of(2, 5));
        stubExport(query);
        when(cohortSampler.sample(query, COHORT)).thenReturn(sampled);

        processor.runQuery(query, result);

        verify(cube).getValuesForKeys(sampled);
    }

    @Test
    void nonSampleQueryExportsTheWholeCohort() {
        Query query = query(false);
        stubExport(query);

        processor.runQuery(query, result);

        verify(cube).getValuesForKeys(COHORT);
        verifyNoInteractions(cohortSampler);
    }

    private void stubExport(Query query) {
        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(COHORT);
        when(queryExecutor.getAllConceptPaths(query)).thenReturn(new LinkedHashSet<>(List.of(CONCEPT)));
        when(phenotypicObservationStore.getCube(CONCEPT)).thenReturn(Optional.of(cube));
    }

    private static Query query(boolean sample) {
        return new Query(List.of(CONCEPT), List.of(), null, List.of(), ResultType.DATAFRAME_TIMESERIES, null, null, sample);
    }
}
