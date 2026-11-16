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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@ExtendWith(MockitoExtension.class)
public class PfbWriterTest {

    private static final String AGE_PATH = "\\demographics\\age\\";
    private static final String STROKE_PATH = "\\phs123\\stroke\\";
    private static final String[] HEADER = {"patient_id", AGE_PATH, STROKE_PATH};
    private static final List<String> REQUESTED_PATHS = List.of(AGE_PATH, STROKE_PATH);

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
