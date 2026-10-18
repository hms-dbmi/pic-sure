package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FailureSinkTest {

    @TempDir
    Path tempDir;

    private static FailureRecord record(int patient, FailureReason reason) {
        return new FailureRecord(
            "run-1", "a.csv", "/in/a.csv", (long) patient, String.valueOf(patient), patient, "\\phs000000\\x\\", null, null, reason,
            "detail"
        );
    }

    @Test
    void writesOneJsonObjectPerLineAndNothingElse() throws IOException {
        Path file = tempDir.resolve("failures.jsonl");
        try (FailureSink sink = new FailureSink(file)) {
            sink.recordFailure(record(1, FailureReason.MISSING_VALUE));
            sink.recordFailure(record(2, FailureReason.INVALID_PATIENT_ID));
        }

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(lines.get(0));
        assertEquals("run-1", first.get("runId").asText());
        assertEquals("MISSING_VALUE", first.get("reasonCode").asText());
        assertEquals("\\phs000000\\x\\", first.get("conceptPath").asText());
        assertFalse(first.has("timestampRaw"), "null fields are omitted");
    }

    @Test
    void rollupCountsFailuresPerReason() throws IOException {
        try (FailureSink sink = new FailureSink(tempDir.resolve("failures.jsonl"))) {
            sink.recordFailure(record(1, FailureReason.MISSING_VALUE));
            sink.recordFailure(record(2, FailureReason.MISSING_VALUE));
            sink.recordFailure(record(3, FailureReason.NUMERIC_PARSE_ERROR));

            assertEquals(3, sink.getTotalFailures());
            assertEquals(Map.of(FailureReason.MISSING_VALUE, 2L, FailureReason.NUMERIC_PARSE_ERROR, 1L), sink.getRollup());
        }
    }

    @Test
    void concurrentWritersDoNotInterleaveLines() throws Exception {
        Path file = tempDir.resolve("failures.jsonl");
        try (FailureSink sink = new FailureSink(file)) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            for (int t = 0; t < 8; t++) {
                int base = t * 1000;
                pool.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        sink.recordFailure(record(base + i, FailureReason.UNKNOWN));
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = Files.readAllLines(file);
        assertEquals(4000, lines.size());
        for (String line : lines) {
            assertNotNull(mapper.readTree(line).get("patientNum"));
        }
    }
}
