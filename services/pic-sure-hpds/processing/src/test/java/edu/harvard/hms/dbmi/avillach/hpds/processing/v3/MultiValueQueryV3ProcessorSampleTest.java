package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.PfbWriter;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiValueQueryV3ProcessorSampleTest {

    private static final Set<Integer> COHORT = Set.of(1, 2, 3, 4, 5, 6, 7, 8);
    private static final String AGE_PATH = "\\demographics\\age\\";
    private static final String LAB_PATH = "\\phs123\\lab\\";

    @Mock
    private QueryExecutor queryExecutor;

    @Mock
    private PhenotypicObservationStore phenotypicObservationStore;

    @Mock
    private CohortSampler cohortSampler;

    @Mock
    private AsyncResult result;

    @Mock
    private DictionaryService dictionaryService;

    @TempDir
    private Path tempDir;

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

    /**
     * Runs a sample PFB export through the real writer. Participants on both sides of the sample have values. The file must hold rows for
     * the sampled participants alone, with their own values.
     */
    @Test
    void samplePfbExportWritesRowsForSampledParticipantsOnly() throws IOException {
        Query query =
            new Query(List.of(AGE_PATH, LAB_PATH), List.of(), null, List.of(), ResultType.DATAFRAME_PFB, null, UUID.randomUUID(), true);
        Set<Integer> cohort = new TreeSet<>(Set.of(1, 2, 3, 4));
        when(queryExecutor.getPatientSubsetForQuery(query)).thenReturn(cohort);
        when(cohortSampler.sample(query, cohort)).thenReturn(new TreeSet<>(Set.of(2, 4)));
        givenColumns(query);
        givenCube(
            cube(AGE_PATH, new KeyAndValue<>(1, "81"), new KeyAndValue<>(2, "82"), new KeyAndValue<>(3, "83"), new KeyAndValue<>(4, "84"))
        );
        givenCube(cube(LAB_PATH, new KeyAndValue<>(2, "inside sample"), new KeyAndValue<>(3, "outside sample")));
        when(dictionaryService.getConcepts(query.select())).thenReturn(List.of());
        File avroFile = tempDir.resolve("result.avro").toFile();
        AsyncResult pfbResult = new AsyncResult(query, processor, new PfbWriter(avroFile, query.id().toString(), dictionaryService));

        processor.runQuery(query, pfbResult);

        List<GenericRecord> patients = patientRows(avroFile);
        assertEquals(List.of("2", "4"), patients.stream().map(patient -> patient.get("patient_id").toString()).toList());
        assertEquals(
            List.of("patient_id", "_demographics_age_", "_phs123_lab_"),
            patients.get(0).getSchema().getFields().stream().map(Schema.Field::name).toList()
        );
        assertEquals(List.of("82"), texts(patients.get(0), "_demographics_age_"));
        assertEquals(List.of("inside sample"), texts(patients.get(0), "_phs123_lab_"));
        assertEquals(List.of("84"), texts(patients.get(1), "_demographics_age_"));
        assertEquals(List.of(), texts(patients.get(1), "_phs123_lab_"));
    }

    private void givenColumns(Query query) {
        Map<String, ColumnMeta> dictionary = new LinkedHashMap<>();
        ArrayList<Integer> columnIndex = new ArrayList<>();
        for (String path : query.select()) {
            dictionary.put(path, new ColumnMeta().setName(path));
            columnIndex.add(columnIndex.size() + 1);
        }
        when(queryExecutor.getDictionary()).thenReturn(dictionary);
        when(queryExecutor.useResidentCubesFirst(query.select(), query.select().size() + 1)).thenReturn(columnIndex);
    }

    private void givenCube(PhenoCube<?> cube) {
        when(phenotypicObservationStore.getCube(cube.name)).thenReturn(Optional.of(cube));
    }

    @SafeVarargs
    private static PhenoCube<?> cube(String path, KeyAndValue<String>... observations) {
        return new PhenoCube<>(path, String.class).setSortedByKey(observations);
    }

    private static List<GenericRecord> patientRows(File avroFile) throws IOException {
        List<GenericRecord> patients = new ArrayList<>();
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(avroFile, new GenericDatumReader<>())) {
            for (GenericRecord entity : reader) {
                if (entity.get("name").toString().startsWith("pic_sure_patients_")) {
                    patients.add((GenericRecord) entity.get("object"));
                }
            }
        }
        return patients;
    }

    private static List<String> texts(GenericRecord record, String field) {
        return ((List<?>) record.get(field)).stream().map(element -> Objects.toString(element, null)).toList();
    }

    private Set<String> exportedIds() {
        verify(result, atLeastOnce()).appendMultiValueResults(rows.capture());
        return rows.getAllValues().stream().flatMap(List::stream).map(row -> row.get(0).get(0)).collect(Collectors.toSet());
    }

    private static Query query(boolean sample) {
        return new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME, null, null, sample);
    }
}
