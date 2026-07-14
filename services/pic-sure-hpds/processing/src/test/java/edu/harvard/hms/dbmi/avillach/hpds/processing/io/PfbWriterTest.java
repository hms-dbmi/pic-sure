package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.Concept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@ExtendWith(MockitoExtension.class)
public class PfbWriterTest {

    @Mock
    private DictionaryService dictionaryService;

    @Test
    public void writeValidPFB() {
        PfbWriter pfbWriter = new PfbWriter(new File("target/test-result.avro"), UUID.randomUUID().toString(), dictionaryService);

        Mockito.when(dictionaryService.getConcepts(List.of("patient_id", "\\demographics\\age\\", "\\phs123\\stroke\\")))
                .thenReturn(List.of(new Concept("\\demographics\\age\\", "age", "AGE", null, "patient age", Map.of("drs_uri", "[\"a-drs.uri\", \"another-drs.uri\"]"))));

        pfbWriter.writeHeader(new String[] {"patient_id", "\\demographics\\age\\", "\\phs123\\stroke\\"});
        List<List<String>> nullableList = new ArrayList<>();
        nullableList.add(List.of("123"));
        nullableList.add(null);
        nullableList.add(List.of("Y"));
        pfbWriter.writeMultiValueEntity(List.of(
                nullableList,
                List.of(List.of("456"), List.of("80") ,List.of("N", "Y")),
                List.of(List.of(), List.of("75"), List.of())
        ));
        pfbWriter.writeMultiValueEntity(List.of(
                List.of(List.of("123"), List.of("80"), List.of("Y")),
                List.of(List.of("456"), List.of("70"),List.of("N", "Y")),
                List.of(List.of(), List.of("75"), List.of())
        ));
        pfbWriter.close();
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