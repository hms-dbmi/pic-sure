package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.Concept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryUnavailableException;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@ExtendWith(MockitoExtension.class)
public class PfbWriterTest {

    private static final String AGE_PATH = "\\demographics\\age\\";
    private static final String STROKE_PATH = "\\phs123\\stroke\\";
    private static final String[] HEADER = {"patient_id", AGE_PATH, STROKE_PATH};
    private static final List<String> REQUESTED_PATHS = List.of(AGE_PATH, STROKE_PATH);

    /**
     * The entity schema of an export of {@link #HEADER} for query id {@code pinned-query} with no timestamp field, as the writer produced
     * it before timestamp fields existed.
     */
    private static final String PINNED_UNDATED_SCHEMA =
        "{\"type\":\"record\",\"name\":\"entity\",\"namespace\":\"edu.harvard.dbmi\",\"fields\":[{\"name\":\"object\",\"type\":[{\"type\":\"record\",\"name\":\"metadata\",\"namespace\":\"\",\"fields\":[{\"name\":\"misc\",\"type\":\"string\"},"
            + "{\"name\":\"nodes\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"record\",\"name\":\"nodes\",\"fields\":[{\"name\":\"name\",\"type\":\"string\"},"
            + "{\"name\":\"ontology_reference\",\"type\":[\"string\",\"null\"],\"default\":\"null\"},"
            + "{\"name\":\"links\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"record\",\"name\":\"Link\",\"fields\":[{\"name\":\"dst\",\"type\":\"string\"},"
            + "{\"name\":\"multiplicity\",\"type\":{\"type\":\"enum\",\"name\":\"Multiplicity\",\"symbols\":[\"ONE_TO_ONE\",\"ONE_TO_MANY\",\"MANY_TO_ONE\",\"MANY_TO_MANY\"]}}]}}},"
            + "{\"name\":\"properties\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"record\",\"name\":\"Property\",\"fields\":[{\"name\":\"name\",\"type\":\"string\"},"
            + "{\"name\":\"ontology_reference\",\"type\":\"string\"},"
            + "{\"name\":\"values\",\"type\":{\"type\":\"map\",\"values\":[\"string\",\"null\"]}}]}}},"
            + "{\"name\":\"values\",\"type\":{\"type\":\"map\",\"values\":[\"string\",\"null\"]}}]}}}]},{\"type\":\"record\",\"name\":\"pic_sure_patients_pinned_query\",\"namespace\":\"\",\"fields\":[{\"name\":\"patient_id\",\"type\":[\"string\",\"null\"],\"default\":\"null\"},"
            + "{\"name\":\"_demographics_age_\",\"type\":{\"type\":\"array\",\"items\":[\"string\",\"null\"]}},"
            + "{\"name\":\"_phs123_stroke_\",\"type\":{\"type\":\"array\",\"items\":[\"string\",\"null\"]}}]},{\"type\":\"record\",\"name\":\"pic_sure_data_dictionary_pinned_query\",\"namespace\":\"\",\"fields\":[{\"name\":\"concept_path\",\"type\":\"string\"},"
            + "{\"name\":\"drs_uri\",\"type\":{\"type\":\"array\",\"items\":[\"string\",\"null\"]}},"
            + "{\"name\":\"display\",\"type\":[\"string\",\"null\"],\"default\":\"null\"},"
            + "{\"name\":\"dataset\",\"type\":[\"string\",\"null\"],\"default\":\"null\"},"
            + "{\"name\":\"description\",\"type\":[\"string\",\"null\"],\"default\":\"null\"}]}]},"
            + "{\"name\":\"id\",\"type\":[\"string\",\"null\"],\"default\":\"null\"},{\"name\":\"name\",\"type\":\"string\"},"
            + "{\"name\":\"relations\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"record\",\"name\":\"Relation\",\"namespace\":\"\",\"fields\":[{\"name\":\"dst_name\",\"type\":\"string\"},"
            + "{\"name\":\"dst_id\",\"type\":\"string\"}]}}}]}";

    @Mock
    private DictionaryService dictionaryService;

    @TempDir
    private Path tempDir;

    private File avroFile;

    @BeforeEach
    public void createAvroFileLocation() {
        avroFile = tempDir.resolve("result.avro").toFile();
    }

    /**
     * Reads every entity record back from an Avro file written by {@link PfbWriter}.
     *
     * @param file the Avro file to read
     * @return the entity records in file order
     */
    private static List<GenericRecord> readEntities(File file) {
        List<GenericRecord> entities = new ArrayList<>();
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(file, new GenericDatumReader<>())) {
            reader.forEach(entities::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entities;
    }

    /**
     * Reads the data dictionary rows back from an Avro file written by {@link PfbWriter}.
     *
     * @param file the Avro file to read
     * @return the data dictionary rows keyed by their formatted concept path
     */
    private static Map<String, GenericRecord> readDictionaryRows(File file) {
        Map<String, GenericRecord> rows = new LinkedHashMap<>();
        for (GenericRecord entity : readEntities(file)) {
            if (entity.get("name").toString().startsWith("pic_sure_data_dictionary_")) {
                rows.put(entity.get("id").toString(), (GenericRecord) entity.get("object"));
            }
        }
        return rows;
    }

    private static String text(GenericRecord record, String field) {
        return Objects.toString(record.get(field), null);
    }

    private static List<List<String>> patientRow(String patientId, String age, String stroke) {
        return List.of(List.of(patientId), List.of(age), List.of(stroke));
    }

    private static void assertNoAvroContent(File file) {
        assertTrue(!file.exists() || file.length() == 0, "expected no Avro content but found " + file.length() + " bytes");
    }

    /**
     * Reads the patient rows back from an Avro file written by {@link PfbWriter}.
     *
     * @param file the Avro file to read
     * @return the patient records in file order
     */
    private static List<GenericRecord> readPatientRows(File file) {
        return readEntities(file).stream().filter(entity -> entity.get("name").toString().startsWith("pic_sure_patients_"))
            .map(entity -> (GenericRecord) entity.get("object")).toList();
    }

    private static List<String> fieldNames(GenericRecord record) {
        return record.getSchema().getFields().stream().map(org.apache.avro.Schema.Field::name).toList();
    }

    private static List<String> texts(GenericRecord record, String field) {
        return ((List<?>) record.get(field)).stream().map(element -> Objects.toString(element, null)).toList();
    }

    /**
     * Reads the property names the metadata record lists for the patient node.
     *
     * @param file the Avro file to read
     * @return the property names in the order they were written
     */
    private static List<String> patientNodeProperties(File file) {
        GenericRecord metadata = (GenericRecord) readEntities(file).get(0).get("object");
        GenericRecord patientNode = ((List<?>) metadata.get("nodes")).stream().map(GenericRecord.class::cast)
            .filter(node -> node.get("name").toString().startsWith("pic_sure_patients_")).findFirst().orElseThrow();
        return ((List<?>) patientNode.get("properties")).stream().map(property -> ((GenericRecord) property).get("name").toString())
            .toList();
    }

    private static String avroSchemaJson(File file) {
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(file, new GenericDatumReader<>())) {
            return reader.getSchema().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<List<String>> timestampRow(List<String> ageTimestamps, List<String> strokeTimestamps) {
        return Arrays.asList(null, ageTimestamps, strokeTimestamps);
    }

    @Test
    public void writeValidPFB() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);

        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(
            List.of(
                new Concept(
                    "\\demographics\\age\\", "age", "AGE", null, "patient age", Map.of("drs_uri", "[\"a-drs.uri\", \"another-drs.uri\"]")
                )
            )
        );

        pfbWriter.writeHeader(new String[] {"patient_id", "\\demographics\\age\\", "\\phs123\\stroke\\"});
        List<List<String>> nullableList = new ArrayList<>();
        nullableList.add(List.of("123"));
        nullableList.add(null);
        nullableList.add(List.of("Y"));
        pfbWriter.writeMultiValueEntity(
            List.of(nullableList, List.of(List.of("456"), List.of("80"), List.of("N", "Y")), List.of(List.of(), List.of("75"), List.of()))
        );
        pfbWriter.writeMultiValueEntity(
            List.of(
                List.of(List.of("123"), List.of("80"), List.of("Y")), List.of(List.of("456"), List.of("70"), List.of("N", "Y")),
                List.of(List.of(), List.of("75"), List.of())
            )
        );
        pfbWriter.close();

        List<GenericRecord> entities = readEntities(avroFile);
        assertEquals(1 + 2 + 6, entities.size());
        assertEquals("metadata", entities.get(0).get("name").toString());
        GenericRecord ageRow = readDictionaryRows(avroFile).get("_demographics_age_");
        assertEquals(List.of("a-drs.uri", "another-drs.uri"), ((List<?>) ageRow.get("drs_uri")).stream().map(Object::toString).toList());
    }

    @Test
    public void writeHeader_alone_makesNoDictionaryCall() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);

        pfbWriter.writeHeader(HEADER);

        Mockito.verifyNoInteractions(dictionaryService);
        assertNoAvroContent(avroFile);
    }

    @Test
    public void writeAndClose_dictionaryRowsCarryConceptDetails_patientIdNotRequested() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(
            List.of(
                new Concept(AGE_PATH, "age", "Age", "demographics", "patient age", Map.of()),
                new Concept(STROKE_PATH, "stroke", "Stroke", "phs123", "history of stroke", null)
            )
        );

        pfbWriter.writeHeader(HEADER);
        pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")));
        pfbWriter.writeMultiValueEntity(List.of(patientRow("456", "70", "N")));
        pfbWriter.close();

        Mockito.verify(dictionaryService, Mockito.times(1)).getConcepts(REQUESTED_PATHS);
        Map<String, GenericRecord> rows = readDictionaryRows(avroFile);
        assertEquals(List.of("_demographics_age_", "_phs123_stroke_"), List.copyOf(rows.keySet()));
        GenericRecord ageRow = rows.get("_demographics_age_");
        assertEquals("Age", text(ageRow, "display"));
        assertEquals("demographics", text(ageRow, "dataset"));
        assertEquals("patient age", text(ageRow, "description"));
        GenericRecord strokeRow = rows.get("_phs123_stroke_");
        assertEquals("Stroke", text(strokeRow, "display"));
        assertEquals("phs123", text(strokeRow, "dataset"));
        assertEquals("history of stroke", text(strokeRow, "description"));
    }

    @Test
    public void writeAndClose_drsUriMetaIsAJsonArray_dictionaryRowCarriesThoseStrings() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(
            List.of(
                new Concept(
                    AGE_PATH, null, "Age", "demographics", "patient age",
                    Map.of("drs_uri", "[\"drs://dg.4503:dg.4503/aaa\", \"drs://dg.4503:dg.4503/bbb\"]")
                ), new Concept(STROKE_PATH, null, "Stroke", "phs123", "history of stroke", Map.of())
            )
        );

        pfbWriter.writeHeader(HEADER);
        pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")));
        pfbWriter.close();

        Map<String, GenericRecord> rows = readDictionaryRows(avroFile);
        assertEquals(List.of("drs://dg.4503:dg.4503/aaa", "drs://dg.4503:dg.4503/bbb"), texts(rows.get("_demographics_age_"), "drs_uri"));
        assertEquals(List.of(), texts(rows.get("_phs123_stroke_"), "drs_uri"));
    }

    @Test
    public void writeMultiValueEntity_dictionaryFails_propagatesAndWritesNoAvro() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        DictionaryUnavailableException failure = new DictionaryUnavailableException("dictionary is down");
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenThrow(failure);
        pfbWriter.writeHeader(HEADER);

        DictionaryUnavailableException thrown = assertThrows(
            DictionaryUnavailableException.class, () -> pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")))
        );

        assertSame(failure, thrown);
        assertNoAvroContent(avroFile);
    }

    @Test
    public void close_noRowsWrittenAndDictionaryFails_propagatesAndWritesNoAvro() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        DictionaryUnavailableException failure = new DictionaryUnavailableException("dictionary is down");
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenThrow(failure);
        pfbWriter.writeHeader(HEADER);

        DictionaryUnavailableException thrown = assertThrows(DictionaryUnavailableException.class, pfbWriter::close);

        assertSame(failure, thrown);
        assertNoAvroContent(avroFile);
    }

    @Test
    public void close_noRowsWritten_writesMetadataAndDictionary() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);

        pfbWriter.close();

        assertEquals(3, readEntities(avroFile).size());
        assertNull(readDictionaryRows(avroFile).get("_demographics_age_").get("display"));
    }

    @Test
    public void writeMultiValueEntity_avroFileCannotBeCreated_nextCallMaterializesAgain() {
        File missingDirectory = tempDir.resolve("missing").toFile();
        File unreachableFile = new File(missingDirectory, "result.avro");
        PfbWriter pfbWriter = new PfbWriter(unreachableFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);

        assertThrows(UncheckedIOException.class, () -> pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y"))));
        assertThrows(UncheckedIOException.class, pfbWriter::close);

        assertTrue(missingDirectory.mkdir());
        pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")));
        pfbWriter.close();

        Mockito.verify(dictionaryService, Mockito.times(3)).getConcepts(REQUESTED_PATHS);
        assertEquals(1 + 2 + 1, readEntities(unreachableFile).size());
    }

    @Test
    public void duplicatePathsInDictionaryResponse_firstWins() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(
            List.of(
                new Concept(AGE_PATH, "age", "First", "first dataset", "first description", null),
                new Concept(AGE_PATH, "age", "Second", "second dataset", "second description", null)
            )
        );

        pfbWriter.writeHeader(HEADER);
        pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")));
        pfbWriter.close();

        GenericRecord ageRow = readDictionaryRows(avroFile).get("_demographics_age_");
        assertEquals("First", text(ageRow, "display"));
        assertEquals("first dataset", text(ageRow, "dataset"));
        assertEquals("first description", text(ageRow, "description"));
    }

    @Test
    public void datedConcept_timestampFieldFollowsConcept_alignedWithValues() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);
        pfbWriter.declareTimestampedFields(Set.of(STROKE_PATH));

        pfbWriter.writeMultiValueEntity(
            List.of(List.of(List.of("123"), List.of("80"), List.of("N", "Y", "N")), List.of(List.of("456"), List.of("70"), List.of())),
            List.of(timestampRow(null, Arrays.asList("2020-01-01T00:00:00Z", null, "1970-01-01T00:00:00Z")), timestampRow(null, null))
        );
        pfbWriter.close();

        List<GenericRecord> patients = readPatientRows(avroFile);
        assertEquals(2, patients.size());
        assertEquals(
            List.of("patient_id", "_demographics_age_", "_phs123_stroke_", "_phs123_stroke__timestamp"), fieldNames(patients.get(0))
        );
        assertEquals(List.of("N", "Y", "N"), texts(patients.get(0), "_phs123_stroke_"));
        assertEquals(
            Arrays.asList("2020-01-01T00:00:00Z", null, "1970-01-01T00:00:00Z"), texts(patients.get(0), "_phs123_stroke__timestamp")
        );
        assertEquals(List.of("80"), texts(patients.get(0), "_demographics_age_"));
        assertEquals(List.of(), texts(patients.get(1), "_phs123_stroke_"));
        assertEquals(List.of(), texts(patients.get(1), "_phs123_stroke__timestamp"));
    }

    @Test
    public void datedConcept_metadataListsTimestampField_dictionaryHasNoRowForIt() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);
        pfbWriter.declareTimestampedFields(Set.of(AGE_PATH));

        pfbWriter.close();

        assertEquals(
            List.of("patient_id", "_demographics_age_", "_demographics_age__timestamp", "_phs123_stroke_"), patientNodeProperties(avroFile)
        );
        assertEquals(List.of("_demographics_age_", "_phs123_stroke_"), List.copyOf(readDictionaryRows(avroFile).keySet()));
        Mockito.verify(dictionaryService, Mockito.times(1)).getConcepts(REQUESTED_PATHS);
    }

    @Test
    public void nothingDeclared_schemaEqualsOneArgumentPath() {
        String queryId = UUID.randomUUID().toString();
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        PfbWriter oneArgumentWriter = new PfbWriter(avroFile, queryId, dictionaryService);
        oneArgumentWriter.writeHeader(HEADER);
        oneArgumentWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")));
        oneArgumentWriter.close();
        File undatedFile = tempDir.resolve("undated.avro").toFile();
        PfbWriter undatedWriter = new PfbWriter(undatedFile, queryId, dictionaryService);
        undatedWriter.writeHeader(HEADER);
        undatedWriter.declareTimestampedFields(Set.of());

        undatedWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")), List.of(timestampRow(null, null)));
        undatedWriter.close();

        assertEquals(avroSchemaJson(avroFile), avroSchemaJson(undatedFile));
        assertEquals(List.of("patient_id", "_demographics_age_", "_phs123_stroke_"), fieldNames(readPatientRows(undatedFile).get(0)));
        assertEquals(List.of("patient_id", "_demographics_age_", "_phs123_stroke_"), patientNodeProperties(undatedFile));
    }

    @Test
    public void nothingDeclared_schemaMatchesPinnedLiteral() {
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        PfbWriter pfbWriter = new PfbWriter(avroFile, "pinned-query", dictionaryService);
        pfbWriter.writeHeader(HEADER);

        pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")));
        pfbWriter.close();

        assertEquals(PINNED_UNDATED_SCHEMA, avroSchemaJson(avroFile));
    }

    @Test
    public void declareTimestampedFields_twiceBeforeRows_secondReplacesFirst() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);
        pfbWriter.declareTimestampedFields(Set.of(AGE_PATH));
        pfbWriter.declareTimestampedFields(Set.of(STROKE_PATH));

        pfbWriter
            .writeMultiValueEntity(List.of(patientRow("123", "80", "Y")), List.of(timestampRow(null, List.of("2020-01-01T00:00:00Z"))));
        pfbWriter.close();

        GenericRecord patient = readPatientRows(avroFile).get(0);
        assertEquals(List.of("patient_id", "_demographics_age_", "_phs123_stroke_", "_phs123_stroke__timestamp"), fieldNames(patient));
        assertEquals(List.of("2020-01-01T00:00:00Z"), texts(patient, "_phs123_stroke__timestamp"));
    }

    @Test
    public void declaredFields_avroFileCannotBeCreated_retryWritesSameTimestampField() {
        File missingDirectory = tempDir.resolve("missing").toFile();
        File unreachableFile = new File(missingDirectory, "result.avro");
        PfbWriter pfbWriter = new PfbWriter(unreachableFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);
        pfbWriter.declareTimestampedFields(Set.of(STROKE_PATH));
        List<List<List<String>>> rows = List.of(patientRow("123", "80", "Y"));
        List<List<List<String>>> timestamps = List.of(timestampRow(null, List.of("2020-01-01T00:00:00Z")));

        assertThrows(UncheckedIOException.class, () -> pfbWriter.writeMultiValueEntity(rows, timestamps));

        assertTrue(missingDirectory.mkdir());
        pfbWriter.writeMultiValueEntity(rows, timestamps);
        pfbWriter.close();

        GenericRecord patient = readPatientRows(unreachableFile).get(0);
        assertEquals(List.of("patient_id", "_demographics_age_", "_phs123_stroke_", "_phs123_stroke__timestamp"), fieldNames(patient));
        assertEquals(List.of("2020-01-01T00:00:00Z"), texts(patient, "_phs123_stroke__timestamp"));
        assertEquals(
            List.of("patient_id", "_demographics_age_", "_phs123_stroke_", "_phs123_stroke__timestamp"),
            patientNodeProperties(unreachableFile)
        );
    }

    @Test
    public void timestampFieldNameCollides_getsNumericSuffix() {
        String basePath = "\\a\\";
        String firstTakenPath = "\\a\\_timestamp";
        String secondTakenPath = "\\a\\_timestamp_2";
        List<String> requestedPaths = List.of(basePath, firstTakenPath, secondTakenPath);
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(requestedPaths)).thenReturn(List.of());
        pfbWriter.writeHeader(new String[] {"patient_id", basePath, firstTakenPath, secondTakenPath});
        pfbWriter.declareTimestampedFields(Set.of(basePath, firstTakenPath));

        pfbWriter.writeMultiValueEntity(
            List.of(List.of(List.of("123"), List.of("x"), List.of("y"), List.of("z"))),
            List.of(Arrays.asList(null, List.of("2020-01-01T00:00:00Z"), List.of("2021-01-01T00:00:00Z"), null))
        );
        pfbWriter.close();

        GenericRecord patient = readPatientRows(avroFile).get(0);
        assertEquals(
            List.of("patient_id", "_a_", "_a__timestamp_3", "_a__timestamp", "_a__timestamp_timestamp", "_a__timestamp_2"),
            fieldNames(patient)
        );
        assertEquals(List.of("2020-01-01T00:00:00Z"), texts(patient, "_a__timestamp_3"));
        assertEquals(List.of("y"), texts(patient, "_a__timestamp"));
        assertEquals(List.of("2021-01-01T00:00:00Z"), texts(patient, "_a__timestamp_timestamp"));
    }

    @Test
    public void declareTimestampedFields_afterRowsWritten_throws() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);
        pfbWriter.writeMultiValueEntity(List.of(patientRow("123", "80", "Y")));

        assertThrows(IllegalStateException.class, () -> pfbWriter.declareTimestampedFields(Set.of(AGE_PATH)));

        pfbWriter.close();
        assertEquals(List.of("patient_id", "_demographics_age_", "_phs123_stroke_"), fieldNames(readPatientRows(avroFile).get(0)));
    }

    @Test
    public void writeMultiValueEntity_timestampsShorterThanValues_throws() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);
        pfbWriter.declareTimestampedFields(Set.of(STROKE_PATH));
        List<List<List<String>>> rows = List.of(List.of(List.of("123"), List.of("80"), List.of("N", "Y")));

        assertThrows(
            IllegalArgumentException.class,
            () -> pfbWriter.writeMultiValueEntity(rows, List.of(timestampRow(null, List.of("2020-01-01T00:00:00Z"))))
        );
        assertThrows(IllegalArgumentException.class, () -> pfbWriter.writeMultiValueEntity(rows, List.of(timestampRow(null, null))));
        assertThrows(IllegalArgumentException.class, () -> pfbWriter.writeMultiValueEntity(rows, List.of()));
        assertThrows(IllegalArgumentException.class, () -> pfbWriter.writeMultiValueEntity(rows));

        pfbWriter.close();
        assertEquals(List.of(), readPatientRows(avroFile));
    }

    @Test
    public void formatFieldName_spacesAndBackslashes_replacedWithUnderscore() {
        PfbWriter pfbWriter = new PfbWriter(new File("target/test-result.avro"), UUID.randomUUID().toString(), dictionaryService);
        String formattedName = pfbWriter.formatFieldName("\\Topmed Study Accession with Subject ID\\\\");
        assertEquals("_Topmed_Study_Accession_with_Subject_ID__", formattedName);
    }

    @Test
    public void formatFieldName_startsWithDigit_prependUnderscore() {
        PfbWriter pfbWriter = new PfbWriter(new File("target/test-result.avro"), UUID.randomUUID().toString(), dictionaryService);
        String formattedName = pfbWriter.formatFieldName("123Topmed Study Accession with Subject ID\\\\");
        assertEquals("_123Topmed_Study_Accession_with_Subject_ID__", formattedName);
    }

    @Test
    public void formatFieldName_randomGarbage_replaceWithUnderscore() {
        PfbWriter pfbWriter = new PfbWriter(new File("target/test-result.avro"), UUID.randomUUID().toString(), dictionaryService);
        String formattedName = pfbWriter.formatFieldName("$$$my garbage @vro var!able nam#");
        assertEquals("___my_garbage__vro_var_able_nam_", formattedName);
    }
}
