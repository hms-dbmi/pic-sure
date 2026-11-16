package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.ResultStoreStream;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.PfbWriter;
import edu.harvard.hms.dbmi.avillach.hpds.processing.timeseries.TimeSeriesConversionService;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiValueQueryV3ProcessorTest {

    private static final String AGE_PATH = "\\demographics\\age\\";
    private static final String VISIT_PATH = "\\phs123\\visit\\";
    private static final long VISIT_TIME = 1_700_000_000_000L;

    @Mock
    private QueryExecutor queryExecutor;

    @Mock
    private PhenotypicObservationStore phenotypicObservationStore;

    @Mock
    private AsyncResult result;

    @Mock
    private ResultStoreStream stream;

    @Mock
    private DictionaryService dictionaryService;

    @Captor
    private ArgumentCaptor<List<List<List<String>>>> valuesCaptor;

    @Captor
    private ArgumentCaptor<List<List<List<String>>>> timestampsCaptor;

    @TempDir
    private Path tempDir;

    private final TimeSeriesConversionService conversionService = new TimeSeriesConversionService();

    private MultiValueQueryV3Processor processor(int idBatchSize) {
        return new MultiValueQueryV3Processor(queryExecutor, phenotypicObservationStore, conversionService, idBatchSize);
    }

    private static Query query(ResultType resultType, String... select) {
        return new Query(List.of(select), List.of(), null, List.of(), resultType, null, UUID.randomUUID());
    }

    @SafeVarargs
    private static PhenoCube<?> cube(String path, KeyAndValue<String>... observations) {
        return new PhenoCube<>(path, String.class).setSortedByKey(observations);
    }

    /**
     * Stubs the patient subset and the column metadata of a query, the way the query executor answers for known concept paths. The column
     * index lists every column twice, as the real executor does.
     *
     * @param query the query under test
     * @param patientIds the patients the query matches
     */
    private void givenPatientsAndColumns(Query query, Integer... patientIds) {
        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(new TreeSet<>(Arrays.asList(patientIds)));
        Map<String, ColumnMeta> dictionary = new LinkedHashMap<>();
        ArrayList<Integer> columnIndex = new ArrayList<>();
        for (String path : query.select()) {
            dictionary.put(path, new ColumnMeta().setName(path));
            columnIndex.add(columnIndex.size() + 1);
        }
        columnIndex.addAll(List.copyOf(columnIndex));
        when(queryExecutor.getDictionary()).thenReturn(dictionary);
        when(queryExecutor.useResidentCubesFirst(query.select(), query.select().size() + 1)).thenReturn(columnIndex);
    }

    private void givenCube(PhenoCube<?> cube) {
        when(phenotypicObservationStore.getCube(cube.name)).thenReturn(Optional.of(cube));
    }

    @Test
    void runQuery_nullAndMinValueTimestamps_notDated() {
        Query query = query(ResultType.DATAFRAME_PFB, VISIT_PATH);
        givenPatientsAndColumns(query, 1);
        givenCube(cube(VISIT_PATH, new KeyAndValue<>(1, "first"), new KeyAndValue<>(1, "second", null)));

        processor(10).runQuery(query, result);

        verify(result, never()).getStream();
        verify(result).appendMultiValueResults(List.of(List.of(List.of("1"), List.of("first", "second"))));
        verify(result).closeWriter();
    }

    @Test
    void runQuery_datedOnlyForPatientOutsideSet_notDeclared() {
        Query query = query(ResultType.DATAFRAME_PFB, VISIT_PATH);
        givenPatientsAndColumns(query, 1);
        givenCube(cube(VISIT_PATH, new KeyAndValue<>(1, "undated", null), new KeyAndValue<>(2, "dated", VISIT_TIME)));

        processor(10).runQuery(query, result);

        verify(result, never()).getStream();
        verify(result).appendMultiValueResults(List.of(List.of(List.of("1"), List.of("undated"))));
    }

    @Test
    void runQuery_datedOnlyInLaterBatch_declaredBeforeFirstAppend() {
        Query query = query(ResultType.DATAFRAME_PFB, VISIT_PATH);
        givenPatientsAndColumns(query, 1, 2);
        givenCube(cube(VISIT_PATH, new KeyAndValue<>(1, "undated", null), new KeyAndValue<>(2, "dated", VISIT_TIME)));

        when(result.getStream()).thenReturn(stream);

        processor(1).runQuery(query, result);

        InOrder order = inOrder(stream, result);
        order.verify(stream).declareTimestampedFields(Set.of(VISIT_PATH));
        order.verify(stream, times(2)).appendMultiValueResults(valuesCaptor.capture(), timestampsCaptor.capture());
        order.verify(result).closeWriter();
        verify(result, never()).appendMultiValueResults(any());
        assertEquals(
            List.of(List.of(List.of(List.of("1"), List.of("undated"))), List.of(List.of(List.of("2"), List.of("dated")))),
            valuesCaptor.getAllValues()
        );
        assertEquals(
            List.of(
                List.of(Arrays.asList(null, Arrays.asList((String) null))),
                List.of(Arrays.asList(null, List.of(conversionService.toISOString(VISIT_TIME))))
            ), timestampsCaptor.getAllValues()
        );
    }

    @Test
    void runQuery_dataframeQuery_neverScansOrDeclares() {
        Query query = query(ResultType.DATAFRAME, VISIT_PATH);
        givenPatientsAndColumns(query, 1);
        givenCube(cube(VISIT_PATH, new KeyAndValue<>(1, "dated", VISIT_TIME)));

        processor(10).runQuery(query, result);

        verify(phenotypicObservationStore, times(1)).getCube(VISIT_PATH);
        verify(queryExecutor, times(1)).useResidentCubesFirst(any(), any(Integer.class));
        verify(result, never()).getStream();
        verify(result).appendMultiValueResults(List.of(List.of(List.of("1"), List.of("dated"))));
    }

    @Test
    void runQuery_datedConcept_timestampsAlignWithValuesInTimeseriesFormat() {
        Query query = query(ResultType.DATAFRAME_PFB, AGE_PATH, VISIT_PATH);
        givenPatientsAndColumns(query, 1, 2);
        givenCube(cube(AGE_PATH, new KeyAndValue<>(1, "80"), new KeyAndValue<>(2, "70")));
        givenCube(
            cube(
                VISIT_PATH, new KeyAndValue<>(1, "epoch", 0L), new KeyAndValue<>(1, "undated", null),
                new KeyAndValue<>(1, "unset", Long.MIN_VALUE), new KeyAndValue<>(1, "later", VISIT_TIME)
            )
        );

        when(result.getStream()).thenReturn(stream);

        processor(10).runQuery(query, result);

        verify(stream).declareTimestampedFields(Set.of(VISIT_PATH));
        verify(phenotypicObservationStore, times(2)).getCube(AGE_PATH);
        verify(phenotypicObservationStore, times(2)).getCube(VISIT_PATH);
        verify(stream).appendMultiValueResults(valuesCaptor.capture(), timestampsCaptor.capture());
        assertEquals(
            Arrays.asList(
                List.of(List.of("1"), List.of("80"), List.of("epoch", "undated", "unset", "later")),
                Arrays.asList(List.of("2"), List.of("70"), null)
            ), valuesCaptor.getValue()
        );
        assertEquals(
            List.of(
                Arrays.asList(
                    null, null, Arrays.asList(conversionService.toISOString(0L), null, null, conversionService.toISOString(VISIT_TIME))
                ), Arrays.asList(null, null, null)
            ), timestampsCaptor.getValue()
        );
        assertEquals("1970-01-01T00:00:00Z", conversionService.toISOString(0L));
        assertEquals(4, valuesCaptor.getValue().get(0).get(2).size());
        assertEquals(4, timestampsCaptor.getValue().get(0).get(2).size());
    }

    @Test
    void runQuery_cubeMissing_notDeclaredAndRowHasNoValues() {
        Query query = query(ResultType.DATAFRAME_PFB, VISIT_PATH);
        givenPatientsAndColumns(query, 1);
        when(phenotypicObservationStore.getCube(VISIT_PATH)).thenReturn(Optional.empty());

        processor(10).runQuery(query, result);

        verify(result, never()).getStream();
        verify(result).appendMultiValueResults(List.of(Arrays.asList(List.of("1"), null)));
        verify(result).closeWriter();
    }

    @Test
    void runQuery_numericCubeWithTimestamps_declaredAndAligned() {
        Query query = query(ResultType.DATAFRAME_PFB, AGE_PATH);
        givenPatientsAndColumns(query, 1);
        KeyAndValue<Double>[] observations = new KeyAndValue[] {new KeyAndValue<>(1, 80.5, VISIT_TIME), new KeyAndValue<>(1, 81.0, null)};
        givenCube(new PhenoCube<>(AGE_PATH, Double.class).setSortedByKey(observations));

        when(result.getStream()).thenReturn(stream);

        processor(10).runQuery(query, result);

        verify(stream).declareTimestampedFields(Set.of(AGE_PATH));
        verify(stream).appendMultiValueResults(valuesCaptor.capture(), timestampsCaptor.capture());
        assertEquals(List.of(List.of(List.of("1"), List.of("80.5", "81.0"))), valuesCaptor.getValue());
        assertEquals(
            List.of(Arrays.asList(null, Arrays.asList(conversionService.toISOString(VISIT_TIME), null))), timestampsCaptor.getValue()
        );
    }

    @Test
    void runQuery_pfbWriter_writesTimestampFieldBesideDatedConcept() throws IOException {
        Query query = query(ResultType.DATAFRAME_PFB, AGE_PATH, VISIT_PATH);
        givenPatientsAndColumns(query, 1, 2);
        givenCube(cube(AGE_PATH, new KeyAndValue<>(1, "80"), new KeyAndValue<>(2, "70")));
        givenCube(cube(VISIT_PATH, new KeyAndValue<>(1, "undated", null), new KeyAndValue<>(2, "dated", VISIT_TIME)));
        when(dictionaryService.getConcepts(List.of(AGE_PATH, VISIT_PATH))).thenReturn(List.of());
        MultiValueQueryV3Processor processor = processor(1);
        File avroFile = tempDir.resolve("result.avro").toFile();
        AsyncResult pfbResult = new AsyncResult(query, processor, new PfbWriter(avroFile, query.id().toString(), dictionaryService));

        processor.runQuery(query, pfbResult);

        List<GenericRecord> patients = new ArrayList<>();
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(avroFile, new GenericDatumReader<>())) {
            for (GenericRecord entity : reader) {
                if (entity.get("name").toString().startsWith("pic_sure_patients_")) {
                    patients.add((GenericRecord) entity.get("object"));
                }
            }
        }
        assertEquals(2, patients.size());
        assertEquals(
            List.of("patient_id", "_demographics_age_", "_phs123_visit_", "_phs123_visit__timestamp"),
            patients.get(0).getSchema().getFields().stream().map(Schema.Field::name).toList()
        );
        assertEquals(Arrays.asList((String) null), texts(patients.get(0), "_phs123_visit__timestamp"));
        assertEquals(List.of("dated"), texts(patients.get(1), "_phs123_visit_"));
        assertEquals(List.of(conversionService.toISOString(VISIT_TIME)), texts(patients.get(1), "_phs123_visit__timestamp"));
    }

    private static List<String> texts(GenericRecord record, String field) {
        return ((List<?>) record.get(field)).stream().map(element -> Objects.toString(element, null)).toList();
    }
}
