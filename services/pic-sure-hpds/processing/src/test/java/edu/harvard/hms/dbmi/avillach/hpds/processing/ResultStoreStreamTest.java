package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.CsvWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultStoreStreamTest {

    private static final String[] HEADER = {"patient_id", "\\study\\sex\\", "\\study\\age\\"};

    @TempDir
    private Path tempDir;

    private File csvFile;

    private ResultStoreStream stream;

    @BeforeEach
    void openStreamOverCsvWriter() throws IOException {
        csvFile = tempDir.resolve("result.csv").toFile();
        stream = new ResultStoreStream(HEADER, new CsvWriter(csvFile));
    }

    private List<String> writtenLines() throws IOException {
        stream.closeWriter();
        return Files.readAllLines(csvFile.toPath());
    }

    private static List<List<String>> row(String patientId, List<String> sexes, List<String> ages) {
        return Arrays.asList(List.of(patientId), sexes, ages);
    }

    @Test
    void appendResults_writesRowsAfterHeader() throws IOException {
        stream.appendResults(List.of(new String[] {"1", "F", "40"}, new String[] {"2", "M", "50"}));

        assertEquals(List.of("patient_id,\\study\\sex\\,\\study\\age\\", "1,F,40", "2,M,50"), writtenLines());
        assertEquals(0, stream.getNumRows());
    }

    @Test
    void appendMultiValueResults_joinsValuesOfOneField() throws IOException {
        stream.appendMultiValueResults(List.of(row("1", List.of("F"), List.of("40", "41")), row("2", null, List.of())));

        assertEquals(List.of("patient_id,\\study\\sex\\,\\study\\age\\", "1,F,40\t41", "2,,"), writtenLines());
    }

    @Test
    void appendMultiValueResults_withTimestampsOnCsvWriter_writesValuesOnly() throws IOException {
        stream.declareTimestampedFields(Set.of("\\study\\age\\"));

        stream.appendMultiValueResults(
            List.of(row("1", List.of("F"), List.of("40", "41"))),
            List.of(Arrays.asList(null, null, Arrays.asList("2020-01-01T00:00:00Z", null)))
        );

        assertEquals(List.of("patient_id,\\study\\sex\\,\\study\\age\\", "1,F,40\t41"), writtenLines());
    }

    @Test
    void appendResultStore_writesEveryStoredRowAndCountsThem() throws IOException {
        ColumnMeta sex = new ColumnMeta().setName("\\study\\sex\\").setWidthInBytes(4).setCategorical(true);
        ColumnMeta age = new ColumnMeta().setName("\\study\\age\\").setWidthInBytes(Double.BYTES).setCategorical(false);
        ResultStore store = new ResultStore("result", List.of(sex, age), new TreeSet<>(List.of(7, 9)));
        store.writeField(1, 0, "F   ".getBytes(StandardCharsets.UTF_8));
        store.writeField(2, 0, ByteBuffer.allocate(Double.BYTES).putDouble(40.5).array());
        store.writeField(1, 1, "M   ".getBytes(StandardCharsets.UTF_8));
        store.writeField(2, 1, ByteBuffer.allocate(Double.BYTES).putDouble(50.0).array());

        stream.appendResultStore(store);

        assertEquals(2, stream.getNumRows());
        assertEquals(List.of("patient_id,\\study\\sex\\,\\study\\age\\", "7,F,40.5", "9,M,50.0"), writtenLines());
    }

    @Test
    void read_afterOpen_returnsFileBytesThenEndOfStream() throws IOException {
        stream.appendResults(List.<String[]>of(new String[] {"1", "F", "40"}));
        stream.closeWriter();
        byte[] expected = Files.readAllBytes(csvFile.toPath());

        stream.open();
        byte[] actual = stream.readAllBytes();

        assertTrue(expected.length > 0);
        assertArrayEquals(expected, actual);
        assertEquals(-1, stream.read());
        assertEquals(expected.length, stream.estimatedSize());
        assertEquals(csvFile, stream.getFile());
        assertEquals(csvFile.toPath().toAbsolutePath(), stream.getTempFilePath());
    }

    @Test
    void close_afterOpen_closesTheFileStream() throws IOException {
        stream.appendResults(List.<String[]>of(new String[] {"1", "F", "40"}));
        stream.closeWriter();
        stream.open();

        stream.close();

        assertThrows(IOException.class, stream::read);
    }

    @Test
    void open_fileMissing_throwsNamingTheFile() {
        stream.closeWriter();
        assertTrue(csvFile.delete());

        RuntimeException thrown = assertThrows(RuntimeException.class, stream::open);

        assertEquals("temp file for result not found : " + csvFile.getAbsolutePath(), thrown.getMessage());
    }
}
