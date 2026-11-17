package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryUnavailableException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.PfbWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncResultTest {

    private static final String AGE_PATH = "\\demographics\\age\\";

    @Mock
    private HpdsV3Processor processor;

    @Mock
    private DictionaryService dictionaryService;

    @TempDir
    private Path tempDir;

    private AsyncResult pfbResult(File avroFile) {
        UUID queryId = UUID.randomUUID();
        Query query = new Query(List.of(AGE_PATH), List.of(), null, List.of(), ResultType.DATAFRAME_PFB, null, queryId);
        when(processor.getHeaderRow(query)).thenReturn(new String[] {"patient_id", AGE_PATH});
        PfbWriter writer = new PfbWriter(avroFile, queryId.toString(), dictionaryService);
        AsyncResult result = new AsyncResult(query, processor, writer).setStatus(AsyncResult.Status.PENDING).setId(queryId.toString());
        doAnswer(invocation -> {
            result.closeWriter();
            return null;
        }).when(processor).runQuery(query, result);
        return result;
    }

    @Test
    void run_dictionaryFailsWhenWriterCloses_endsInError() {
        File avroFile = tempDir.resolve("result.avro").toFile();
        AsyncResult result = pfbResult(avroFile);
        when(dictionaryService.getConcepts(List.of(AGE_PATH))).thenThrow(new DictionaryUnavailableException("dictionary is down"));

        result.run();

        assertEquals(AsyncResult.Status.ERROR, result.getStatus());
        assertTrue(!avroFile.exists() || avroFile.length() == 0);
    }

    @Test
    void run_dictionaryAnswers_endsInSuccess() {
        AsyncResult result = pfbResult(tempDir.resolve("result.avro").toFile());
        when(dictionaryService.getConcepts(List.of(AGE_PATH))).thenReturn(List.of());

        result.run();

        assertEquals(AsyncResult.Status.SUCCESS, result.getStatus());
    }
}
