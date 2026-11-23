package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.Concept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryUnavailableException;
import org.apache.avro.Schema;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@ExtendWith(MockitoExtension.class)
public class PfbWriterTest {

    private static final String QUERY_ID = "0b6c4f8a-1111-2222-3333-444455556666";
    private static final String PATIENT_NODE = "pic_sure_patients_0b6c4f8a_1111_2222_3333_444455556666";
    private static final String DICTIONARY_NODE = "pic_sure_data_dictionary_0b6c4f8a_1111_2222_3333_444455556666";
    private static final String AGE_PATH = "\\demographics\\age\\";
    private static final String STROKE_PATH = "\\phs123\\stroke\\";
    private static final String[] HEADER = {"patient_id", AGE_PATH, STROKE_PATH};
    private static final List<String> REQUESTED_PATHS = List.of(AGE_PATH, STROKE_PATH);

    @Mock
    private DictionaryService dictionaryService;

    @TempDir
    Path tempDir;

    private File avroFile;

    @BeforeEach
    public void createAvroFileLocation() {
        avroFile = tempDir.resolve("result.avro").toFile();
    }

    @Test
    public void writeValidPFB() throws IOException {
        File file = tempDir.resolve("result.avro").toFile();
        PfbWriter pfbWriter = new PfbWriter(file, QUERY_ID, dictionaryService);

        Mockito.when(dictionaryService.getConcepts(List.of("\\demographics\\age\\", "\\phs123\\stroke\\"))).thenReturn(
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
        pfbWriter.writeMultiValueEntity(List.of(nullableList, List.of(List.of("456"), List.of("80"), List.of("N", "Y"))));
        pfbWriter.close();

        List<GenericRecord> entities = readAll(file);
        assertEquals(5, entities.size());

        GenericRecord metadata = entities.get(0);
        assertEquals("Metadata", metadata.get("name").toString());
        assertNull(metadata.get("id"));
        List<?> nodes = (List<?>) ((GenericRecord) metadata.get("object")).get("nodes");
        assertEquals(
            List.of(PATIENT_NODE, DICTIONARY_NODE), nodes.stream().map(node -> ((GenericRecord) node).get("name").toString()).toList()
        );
        GenericRecord ageProperty = (GenericRecord) ((List<?>) ((GenericRecord) nodes.get(0)).get("properties")).get(1);
        assertEquals("_demographics_age_", ageProperty.get("name").toString());
        assertEquals(
            Map.of("concept_path", "\\demographics\\age\\", "display", "AGE", "description", "patient age"),
            stringMap((Map<?, ?>) ageProperty.get("values"))
        );

        GenericRecord ageDictionary = entities.get(1);
        assertEquals(DICTIONARY_NODE, ageDictionary.get("name").toString());
        assertEquals("_demographics_age_", ageDictionary.get("id").toString());
        GenericRecord ageDictionaryRow = (GenericRecord) ageDictionary.get("object");
        assertEquals("\\demographics\\age\\", ageDictionaryRow.get("concept_path").toString());
        assertEquals("AGE", ageDictionaryRow.get("display").toString());
        assertEquals(List.of("a-drs.uri", "another-drs.uri"), strings((List<?>) ageDictionaryRow.get("drs_uri")));

        GenericRecord strokeDictionaryRow = (GenericRecord) entities.get(2).get("object");
        assertEquals("\\phs123\\stroke\\", strokeDictionaryRow.get("concept_path").toString());
        assertNull(strokeDictionaryRow.get("display"));

        GenericRecord patient = entities.get(4);
        assertEquals(PATIENT_NODE, patient.get("name").toString());
        assertEquals("456", patient.get("id").toString());
        GenericRecord patientRow = (GenericRecord) patient.get("object");
        assertEquals("456", patientRow.get("patient_id").toString());
        assertEquals(List.of("80"), strings((List<?>) patientRow.get("_demographics_age_")));
        assertEquals(List.of("N", "Y"), strings((List<?>) patientRow.get("_phs123_stroke_")));
        assertEquals(List.of(), strings((List<?>) ((GenericRecord) entities.get(3).get("object")).get("_demographics_age_")));
    }

    @Test
    public void writeMultiValueEntity_twoBatches_appendsEveryPatientRow() throws IOException {
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

        List<GenericRecord> entities = readAll(avroFile);
        assertEquals(1 + 2 + 6, entities.size());
        assertEquals("Metadata", entities.get(0).get("name").toString());
        GenericRecord ageRow = readDictionaryRows(avroFile).get("_demographics_age_");
        assertEquals(List.of("a-drs.uri", "another-drs.uri"), strings((List<?>) ageRow.get("drs_uri")));
    }

    @Test
    public void writeHeader_envelopeMatchesPypfb() throws IOException {
        File file = tempDir.resolve("result.avro").toFile();
        Mockito.when(dictionaryService.getConcepts(List.of("\\demographics\\age\\"))).thenReturn(List.of());
        PfbWriter pfbWriter = new PfbWriter(file, QUERY_ID, dictionaryService);
        pfbWriter.writeHeader(new String[] {"patient_id", "\\demographics\\age\\"});
        pfbWriter.close();

        Schema schema;
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(file, new GenericDatumReader<>())) {
            schema = reader.getSchema();
        }
        assertEquals("Entity", schema.getFullName());
        assertEquals(List.of("id", "name", "object", "relations"), schema.getFields().stream().map(Schema.Field::name).toList());
        List<Schema> objectTypes = schema.getField("object").schema().getTypes();
        assertEquals(List.of("Metadata", PATIENT_NODE, DICTIONARY_NODE), objectTypes.stream().map(Schema::getFullName).toList());
        Schema link = objectTypes.getFirst().getField("nodes").schema().getElementType().getField("links").schema().getElementType();
        assertEquals(List.of("multiplicity", "dst", "name"), link.getFields().stream().map(Schema.Field::name).toList());
        assertEquals(Schema.Type.MAP, objectTypes.getFirst().getField("misc").schema().getType());
    }

    @Test
    public void writeHeader_collidingFieldNames_getUniqueColumns() throws IOException {
        File file = tempDir.resolve("result.avro").toFile();
        Mockito.when(dictionaryService.getConcepts(List.of("\\a b\\", "\\a_b\\"))).thenReturn(
            List.of(new Concept("\\a_b\\", "a_b", "Underscore", null, null, null), new Concept("\\a b\\", "a b", "Space", null, null, null))
        );
        PfbWriter pfbWriter = new PfbWriter(file, QUERY_ID, dictionaryService);
        pfbWriter.writeHeader(new String[] {"patient_id", "\\a b\\", "\\a_b\\"});
        pfbWriter.writeMultiValueEntity(List.of(List.of(List.of("1"), List.of("x"), List.of("y"))));
        pfbWriter.close();

        List<GenericRecord> entities = readAll(file);
        assertEquals("\\a b\\", ((GenericRecord) entities.get(1).get("object")).get("concept_path").toString());
        assertEquals("_a_b_", entities.get(1).get("id").toString());
        assertEquals("Space", text((GenericRecord) entities.get(1).get("object"), "display"));
        assertEquals("\\a_b\\", ((GenericRecord) entities.get(2).get("object")).get("concept_path").toString());
        assertEquals("_a_b__2", entities.get(2).get("id").toString());
        assertEquals("Underscore", text((GenericRecord) entities.get(2).get("object"), "display"));
        GenericRecord patientRow = (GenericRecord) entities.get(3).get("object");
        assertEquals(List.of("x"), strings((List<?>) patientRow.get("_a_b_")));
        assertEquals(List.of("y"), strings((List<?>) patientRow.get("_a_b__2")));
    }

    @Test
    public void writeHeader_dictionaryFails_failsTheExportAndWritesNoAvro() {
        File file = tempDir.resolve("result.avro").toFile();
        RuntimeException failure = new RuntimeException("dictionary down");
        Mockito.when(dictionaryService.getConcepts(Mockito.any())).thenThrow(failure);
        PfbWriter pfbWriter = new PfbWriter(file, QUERY_ID, dictionaryService);
        pfbWriter.writeHeader(new String[] {"patient_id", "\\demographics\\age\\"});

        RuntimeException thrown = assertThrows(RuntimeException.class, pfbWriter::close);

        assertSame(failure, thrown);
        assertNoAvroContent(file);
    }

    @Test
    public void constructor_nullDictionaryService_rejectedWithMessageNamingIt() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> new PfbWriter(avroFile, QUERY_ID, null));

        assertEquals("PfbWriter requires a DictionaryService", thrown.getMessage());
        assertNoAvroContent(avroFile);
    }

    @Test
    public void writeHeader_alone_makesNoDictionaryCall() {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);

        pfbWriter.writeHeader(HEADER);

        Mockito.verifyNoInteractions(dictionaryService);
        assertNoAvroContent(avroFile);
    }

    @Test
    public void writeAndClose_dictionaryRowsCarryConceptDetails_patientIdNotRequested() throws IOException {
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
    public void writeAndClose_pathFormattedLikePatientId_isRequestedAndWrittenAsItsOwnColumn() throws IOException {
        String patientSpaceId = "patient id";
        PfbWriter pfbWriter = new PfbWriter(avroFile, QUERY_ID, dictionaryService);
        Mockito.when(dictionaryService.getConcepts(List.of(patientSpaceId)))
            .thenReturn(List.of(new Concept(patientSpaceId, "patient id", "Patient Id Concept", "phs123", null, null)));

        pfbWriter.writeHeader(new String[] {"patient_id", patientSpaceId});
        pfbWriter.writeMultiValueEntity(List.of(List.of(List.of("123"), List.of("a", "b"))));
        pfbWriter.close();

        Mockito.verify(dictionaryService, Mockito.times(1)).getConcepts(List.of(patientSpaceId));
        Mockito.verifyNoMoreInteractions(dictionaryService);
        Map<String, GenericRecord> rows = readDictionaryRows(avroFile);
        assertEquals(List.of("patient_id_2"), List.copyOf(rows.keySet()));
        assertEquals(patientSpaceId, text(rows.get("patient_id_2"), "concept_path"));
        assertEquals("Patient Id Concept", text(rows.get("patient_id_2"), "display"));
        GenericRecord patient = readAll(avroFile).getLast();
        assertEquals(PATIENT_NODE, patient.get("name").toString());
        assertEquals("123", patient.get("id").toString());
        GenericRecord patientRow = (GenericRecord) patient.get("object");
        assertEquals("123", patientRow.get("patient_id").toString());
        assertEquals(List.of("a", "b"), strings((List<?>) patientRow.get("patient_id_2")));
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
    public void close_noRowsWritten_writesMetadataAndDictionary() throws IOException {
        PfbWriter pfbWriter = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        Mockito.when(dictionaryService.getConcepts(REQUESTED_PATHS)).thenReturn(List.of());
        pfbWriter.writeHeader(HEADER);

        pfbWriter.close();

        assertEquals(3, readAll(avroFile).size());
        assertNull(readDictionaryRows(avroFile).get("_demographics_age_").get("display"));
    }

    @Test
    public void writeMultiValueEntity_avroFileCannotBeCreated_nextCallMaterializesAgain() throws IOException {
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
        assertEquals(1 + 2 + 1, readAll(unreachableFile).size());
    }

    @Test
    public void duplicatePathsInDictionaryResponse_firstWins() throws IOException {
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

    private static List<GenericRecord> readAll(File file) throws IOException {
        List<GenericRecord> entities = new ArrayList<>();
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(file, new GenericDatumReader<>())) {
            reader.forEach(entities::add);
        }
        return entities;
    }

    /**
     * Reads the data dictionary rows back from an Avro file written by {@link PfbWriter}.
     *
     * @param file the Avro file to read
     * @return the data dictionary rows keyed by their entity id, the formatted field name of the concept
     */
    private static Map<String, GenericRecord> readDictionaryRows(File file) throws IOException {
        Map<String, GenericRecord> rows = new LinkedHashMap<>();
        for (GenericRecord entity : readAll(file)) {
            if (entity.get("name").toString().startsWith("pic_sure_data_dictionary_")) {
                rows.put(entity.get("id").toString(), (GenericRecord) entity.get("object"));
            }
        }
        return rows;
    }

    private static String text(GenericRecord record, String field) {
        return Objects.toString(record.get(field), null);
    }

    private static List<String> strings(List<?> values) {
        return values.stream().map(value -> value == null ? null : value.toString()).toList();
    }

    private static Map<String, String> stringMap(Map<?, ?> values) {
        Map<String, String> out = new HashMap<>();
        values.forEach((key, value) -> out.put(key.toString(), value.toString()));
        return out;
    }

    private static List<List<String>> patientRow(String patientId, String age, String stroke) {
        return List.of(List.of(patientId), List.of(age), List.of(stroke));
    }

    private static void assertNoAvroContent(File file) {
        assertTrue(!file.exists() || file.length() == 0, "expected no Avro content but found " + file.length() + " bytes");
    }
}
