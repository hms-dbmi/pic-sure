package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DumpSourceCSVTest {

    @Test
    void writesTheDumpOwnerOnly(@TempDir Path dir) throws Exception {
        ExportForMigrationTest.writeStore(dir);

        DumpSourceCSV.dump(dir);

        Path output = dir.resolve(DumpSourceCSV.OUTPUT_FILE);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(output)));
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("9,") && line.contains(",B,-5")), lines.toString());
    }

    @Test
    void refusesAnExistingDumpAndLeavesItUntouched(@TempDir Path dir) throws Exception {
        ExportForMigrationTest.writeStore(dir);
        Path output = dir.resolve(DumpSourceCSV.OUTPUT_FILE);
        Files.writeString(output, "an earlier dump");

        FileAlreadyExistsException refused = assertThrows(FileAlreadyExistsException.class, () -> DumpSourceCSV.dump(dir));

        assertTrue(refused.getMessage().contains(output.toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains("move or delete it"), refused.getMessage());
        assertEquals("an earlier dump", Files.readString(output));
    }

    @Test
    void createsNothingWithoutAKey(@TempDir Path dir) throws Exception {
        ExportForMigrationTest.writeStore(dir);
        Files.delete(dir.resolve("encryption_key"));

        assertThrows(IllegalStateException.class, () -> DumpSourceCSV.dump(dir));

        assertFalse(Files.exists(dir.resolve(DumpSourceCSV.OUTPUT_FILE)));
    }

    @Test
    void leavesNoPartialDumpWhenAConceptCannotBeLoaded(@TempDir Path dir) throws Exception {
        ExportForMigrationTest.writeStore(dir);
        Path observations = dir.resolve("allObservationsStore.javabin");
        Files.write(observations, new byte[(int) Files.size(observations)]);

        assertThrows(RuntimeException.class, () -> DumpSourceCSV.dump(dir));

        assertFalse(Files.exists(dir.resolve(DumpSourceCSV.OUTPUT_FILE)));
        ExportForMigrationTest.writeStore(dir);
        DumpSourceCSV.dump(dir);
        assertTrue(Files.exists(dir.resolve(DumpSourceCSV.OUTPUT_FILE)));
    }
}
