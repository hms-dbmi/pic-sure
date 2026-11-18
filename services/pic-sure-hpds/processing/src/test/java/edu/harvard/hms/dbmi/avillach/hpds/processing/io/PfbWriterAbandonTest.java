package edu.harvard.hms.dbmi.avillach.hpds.processing.io;

import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PfbWriterAbandonTest {

    private static final String AGE_PATH = "\\demographics\\age\\";
    private static final String[] HEADER = {"patient_id", AGE_PATH};

    @Mock
    private DictionaryService dictionaryService;

    @TempDir
    private Path tempDir;

    private File avroFile;

    @BeforeEach
    void createAvroFileLocation() {
        avroFile = tempDir.resolve("result.avro").toFile();
    }

    @Test
    void abandonBeforeAnyRowMakesNoDictionaryCallAndWritesNothing() {
        PfbWriter writer = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        writer.writeHeader(HEADER);

        writer.abandon();

        verifyNoInteractions(dictionaryService);
        assertFalse(avroFile.exists());
    }

    @Test
    void abandonAfterRowsClosesTheFileWithoutAnotherDictionaryCall() throws IOException {
        when(dictionaryService.getConcepts(List.of(AGE_PATH))).thenReturn(List.of());
        PfbWriter writer = new PfbWriter(avroFile, UUID.randomUUID().toString(), dictionaryService);
        writer.writeHeader(HEADER);
        writer.writeMultiValueEntity(List.of(List.of(List.of("1"), List.of("42"))));

        writer.abandon();

        verify(dictionaryService, times(1)).getConcepts(List.of(AGE_PATH));
        try (DataFileReader<GenericRecord> reader = new DataFileReader<>(avroFile, new GenericDatumReader<>())) {
            int entities = 0;
            while (reader.hasNext()) {
                reader.next();
                entities++;
            }
            assertEquals(3, entities);
        }
    }
}
