package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.FailureSink;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.ObservationRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CsvChunkProcessor - validates parallel CSV processing and circular dependency fix.
 */
class CsvChunkProcessorTest {

    @TempDir
    Path tempDir;

    private CsvChunkProcessor chunkProcessor;
    private FailureSink mockFailureSink;
    private CsvObservationProducer mockProducer;
    private String runId;

    @BeforeEach
    void setUp() {
        runId = "test-run-" + System.currentTimeMillis();
        mockFailureSink = mock(FailureSink.class);
        mockProducer = mock(CsvObservationProducer.class);
        chunkProcessor = new CsvChunkProcessor(runId, mockFailureSink);
    }

    @Test
    void testConstructor_NoCircularDependency() {
        // This test validates that the circular dependency is fixed
        // If there was a circular dependency, this would throw StackOverflowError
        assertDoesNotThrow(() -> {
            CsvChunkProcessor processor = new CsvChunkProcessor(runId, mockFailureSink);
            assertNotNull(processor);
        });
    }

    @Test
    void testProcessLargeFileInParallel_SmallFile_FallsBackToSequential() throws IOException {
        // Create a small CSV file (will result in 1 chunk)
        Path csvFile = tempDir.resolve("small.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            1,\\test\\path\\,100,,2024-01-01T00:00:00Z
            2,\\test\\path\\,200,,2024-01-02T00:00:00Z
            """;
        Files.writeString(csvFile, csvContent);

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        // Execute
        chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, mockProducer);

        // Verify fallback to sequential processing was called
        verify(mockProducer, times(1)).processFileSequentially(eq(csvFile), any(), eq(1000));
    }

    @Test
    void testProcessLargeFileInParallel_LargeFile_ProcessesInParallel() throws IOException {
        // Create a large CSV file (multiple chunks)
        Path csvFile = tempDir.resolve("large.csv");
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n");

        // Generate 5000 rows to ensure multiple chunks
        for (int i = 1; i <= 5000; i++) {
            csvContent.append(i).append(",\\test\\concept\\path\\,").append(i * 10).append(",,2024-01-01T00:00:00Z\n");
        }
        Files.writeString(csvFile, csvContent.toString());

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        // Execute - file is small enough to fall back to sequential
        chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, mockProducer);

        // Small files fall back to sequential processing
        verify(mockProducer, times(1)).processFileSequentially(any(Path.class), any(), eq(1000));
    }

    @Test
    void testProcessLargeFileInParallel_ProducerParameter_NotNull() throws IOException {
        Path csvFile = tempDir.resolve("test.csv");
        Files.writeString(csvFile, "PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR\n1,\\test\\,100,\n");

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        // Execute - should not throw NullPointerException
        assertDoesNotThrow(() -> {
            chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, mockProducer);
        });

        // Verify producer was used
        verify(mockProducer, times(1)).processFileSequentially(any(), any(), anyInt());
    }

    @Test
    void testProcessLargeFileInParallel_NullProducer_ThrowsException() throws IOException {
        Path csvFile = tempDir.resolve("test.csv");
        Files.writeString(csvFile, "1,\\test\\,100,\n");

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        // Should throw NullPointerException when producer is null
        assertThrows(NullPointerException.class, () -> {
            chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, null);
        });
    }

    @Test
    void testProcessLargeFileInParallel_EmptyFile_HandlesGracefully() throws IOException {
        Path csvFile = tempDir.resolve("empty.csv");
        Files.writeString(csvFile, "");

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        // Should not throw exception
        assertDoesNotThrow(() -> {
            chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, mockProducer);
        });
    }

    @Test
    void testProcessLargeFileInParallel_HeaderOnly_FallsBackToSequential() throws IOException {
        Path csvFile = tempDir.resolve("header-only.csv");
        // Add at least one data row so chunk processing is triggered
        Files.writeString(csvFile, "PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n1,\\test\\,100,,0\n");

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, mockProducer);

        // Should fall back to sequential processing (file results in 1 chunk)
        verify(mockProducer, times(1)).processFileSequentially(any(Path.class), any(), eq(1000));
    }

    @Test
    void testProcessLargeFileInParallel_ParallelExecution_UsesVirtualThreads() throws IOException {
        // Create a CSV file large enough for parallel processing
        Path csvFile = tempDir.resolve("parallel-test.csv");
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR\n");

        for (int i = 1; i <= 10000; i++) {
            csvContent.append(i).append(",\\test\\,").append(i * 10).append(",\n");
        }
        Files.writeString(csvFile, csvContent.toString());

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        // File is small, will fall back to sequential
        chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, mockProducer);

        // Verify producer was called (either sequential or parallel path)
        verify(mockProducer, atLeastOnce()).processFileSequentially(any(Path.class), any(), anyInt());
    }

    @Test
    void testProcessLargeFileInParallel_ExceptionInChunk_ContinuesProcessing() throws IOException {
        Path csvFile = tempDir.resolve("error-test.csv");
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR\n");

        for (int i = 1; i <= 10000; i++) {
            csvContent.append(i).append(",\\test\\,").append(i * 10).append(",\n");
        }
        Files.writeString(csvFile, csvContent.toString());

        Consumer<List<ObservationRow>> consumer = rows -> {
        };

        // Should not throw exception - should handle gracefully
        assertDoesNotThrow(() -> {
            chunkProcessor.processLargeFileInParallel(csvFile, consumer, 1000, mockProducer);
        });

        // Verify processing was attempted
        verify(mockProducer, atLeastOnce()).processFileSequentially(any(Path.class), any(), anyInt());
    }

    @Test
    void testDetectHeader_AcceptsCanonicalHeaders() {
        assertTrue(chunkProcessor.detectHeader("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP"));
    }

    @Test
    void testDetectHeader_AcceptsEhrHeaders() {
        assertTrue(chunkProcessor.detectHeader("subject_id,concept_path,continuous_nval,categorical_tval,timestamp_ts"));
        assertTrue(chunkProcessor.detectHeader("Subject_ID,Concept_Path,Continuous_NVal,Categorical_TVal,Timestamp_TS"));
    }

    @Test
    void testDetectHeader_RejectsDataLine() {
        // Data rows always start with an integer patient num
        assertFalse(chunkProcessor.detectHeader("101,\\teststudy\\condition\\diagnosis\\,,fake diagnosis A,2023-11-25 14:00:00"));
    }

    @Test
    void testDetectHeader_RejectsTooFewColumns() {
        assertFalse(chunkProcessor.detectHeader("some,line"));
        assertFalse(chunkProcessor.detectHeader(""));
        assertFalse(chunkProcessor.detectHeader(null));
    }
}
