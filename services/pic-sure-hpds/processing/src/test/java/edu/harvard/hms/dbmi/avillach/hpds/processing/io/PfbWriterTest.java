package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.Concept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@ExtendWith(MockitoExtension.class)
public class PfbWriterTest {

    private static final String QUERY_ID = "0b6c4f8a-1111-2222-3333-444455556666";
    private static final String PATIENT_NODE = "pic_sure_patients_0b6c4f8a_1111_2222_3333_444455556666";
    private static final String DICTIONARY_NODE = "pic_sure_data_dictionary_0b6c4f8a_1111_2222_3333_444455556666";

    @Mock
    private DictionaryService dictionaryService;

    @TempDir
    Path tempDir;

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
    public void writeHeader_envelopeMatchesPypfb() throws IOException {
        File file = tempDir.resolve("result.avro").toFile();
        PfbWriter pfbWriter = new PfbWriter(file, QUERY_ID, null);
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
        PfbWriter pfbWriter = new PfbWriter(file, QUERY_ID, null);
        pfbWriter.writeHeader(new String[] {"patient_id", "\\a b\\", "\\a_b\\"});
        pfbWriter.writeMultiValueEntity(List.of(List.of(List.of("1"), List.of("x"), List.of("y"))));
        pfbWriter.close();

        List<GenericRecord> entities = readAll(file);
        assertEquals("\\a b\\", ((GenericRecord) entities.get(1).get("object")).get("concept_path").toString());
        assertEquals("_a_b_", entities.get(1).get("id").toString());
        assertEquals("\\a_b\\", ((GenericRecord) entities.get(2).get("object")).get("concept_path").toString());
        assertEquals("_a_b__2", entities.get(2).get("id").toString());
        GenericRecord patientRow = (GenericRecord) entities.get(3).get("object");
        assertEquals(List.of("x"), strings((List<?>) patientRow.get("_a_b_")));
        assertEquals(List.of("y"), strings((List<?>) patientRow.get("_a_b__2")));
    }

    @Test
    public void writeHeader_dictionaryFails_writesWithoutConceptMetadata() throws IOException {
        File file = tempDir.resolve("result.avro").toFile();
        Mockito.when(dictionaryService.getConcepts(Mockito.any())).thenThrow(new RuntimeException("dictionary down"));
        PfbWriter pfbWriter = new PfbWriter(file, QUERY_ID, dictionaryService);
        pfbWriter.writeHeader(new String[] {"patient_id", "\\demographics\\age\\"});
        pfbWriter.close();

        GenericRecord dictionaryRow = (GenericRecord) readAll(file).get(1).get("object");
        assertEquals("\\demographics\\age\\", dictionaryRow.get("concept_path").toString());
        assertNull(dictionaryRow.get("description"));
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

    private static List<String> strings(List<?> values) {
        return values.stream().map(value -> value == null ? null : value.toString()).toList();
    }

    private static Map<String, String> stringMap(Map<?, ?> values) {
        Map<String, String> out = new HashMap<>();
        values.forEach((key, value) -> out.put(key.toString(), value.toString()));
        return out;
    }
}
