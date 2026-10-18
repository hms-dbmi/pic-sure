package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.HPDSWriterAdapter.WriterStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HPDSWriterAdapterTest {

    private static final String A = "\\phs000000\\Study\\A\\";
    private static final String B = "\\phs000000\\Study\\B\\";

    @TempDir
    Path tempDir;

    private Path out;
    private HPDSWriterAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        StoreFixture.loadDefaultKey(tempDir);
        out = tempDir.resolve("out");
        adapter = new HPDSWriterAdapter(tempDir.resolve("spool"), out, Crypto.DEFAULT_KEY_NAME, 4, 1_000, 2, 10);
    }

    @Test
    void storesRowsWithTimestampsAndCountsEveryOutcome() throws Exception {
        Instant t = Instant.parse("2021-01-15T10:30:00Z");
        int stored = adapter.acceptBatch(
            List.of(
                new ObservationRow(1, A, 12.5, null, t), new ObservationRow(2, A, 13.5, null, null),
                new ObservationRow(1, B, null, "yes", t), new ObservationRow(2, B, null, "no", t)
            )
        );
        assertEquals(4, stored);
        assertEquals(new WriterStats(4, 0, 0, 0, 0), adapter.getStats());
        adapter.closeAndSave();

        ColumnMeta numeric = StoreFixture.readColumnMeta(out).get(A);
        assertTrue(numeric.hasTimestamp());
        assertEquals(t.toEpochMilli(), numeric.getTimestampMin());
        KeyAndValue<?>[] sorted = StoreFixture.readCube(out, A).sortedByKey();
        assertEquals(t.toEpochMilli(), sorted[0].getTimestamp());
        assertNull(sorted[1].getTimestamp());
        assertEquals(2, adapter.getPatientCount());
        assertEquals(2, adapter.getConceptCount());
        assertTrue(adapter.getFailedConcepts().isEmpty());
        PhenoCube<?> categorical = StoreFixture.readCube(out, B);
        assertTrue(categorical.isStringType());
    }

    @Test
    void rowsWithNoValueOrBothValuesAreCountedAsInvalidAndNotStored() throws Exception {
        int stored = adapter.acceptBatch(
            List.of(
                new ObservationRow(1, A, 1.0, "also text", null), new ObservationRow(2, A, null, null, null),
                new ObservationRow(3, A, 3.0, null, null)
            )
        );

        assertEquals(1, stored);
        assertEquals(new WriterStats(1, 2, 0, 0, 0), adapter.getStats());
        adapter.closeAndSave();
        assertEquals(1, StoreFixture.readColumnMeta(out).get(A).getObservationCount());
    }

    @Test
    void nullSentinelsAndConceptLimitAreReportedInTheStats() throws Exception {
        HPDSWriterAdapter limited =
            new HPDSWriterAdapter(tempDir.resolve("spool2"), tempDir.resolve("out2"), Crypto.DEFAULT_KEY_NAME, 4, 2, 2, 10);
        limited.acceptBatch(
            List.of(
                new ObservationRow(1, A, 1.0, null, null), new ObservationRow(2, A, null, "NA", null),
                new ObservationRow(3, A, 3.0, null, null), new ObservationRow(4, A, 4.0, null, null)
            )
        );

        assertEquals(new WriterStats(2, 0, 1, 0, 0), limited.getStats(), "held sentinels are settled when the store is saved");
        limited.closeAndSave();
        assertEquals(new WriterStats(2, 0, 1, 1, 0), limited.getStats());
    }

    @Test
    void acceptingAfterCloseFailsAndClosingTwiceIsHarmless() throws Exception {
        adapter.acceptBatch(List.of(new ObservationRow(1, A, 1.0, null, null)));
        adapter.close();

        assertThrows(IllegalStateException.class, () -> adapter.acceptBatch(List.of(new ObservationRow(2, A, 2.0, null, null))));
        assertDoesNotThrow(adapter::closeAndSave);
        assertEquals(Map.of(), adapter.getFailedConcepts());
    }
}
