package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DumpGenomicCanonicalTest {

    @Test
    void theOutputIsCreatedOwnerOnly(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectory(dir.resolve("root"));
        Path output = dir.resolve("dump.txt");
        try (OutputStream out = DumpGenomicCanonical.openPrivateOutput(output, root)) {
            out.write("M\tlocal\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(output)));
        assertEquals("M\tlocal\n", Files.readString(output));
    }

    @Test
    void anExistingOutputPathIsRefusedUntouched(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Path output = Files.writeString(dir.resolve("dump.txt"), "keep");
        assertThrows(IOException.class, () -> DumpGenomicCanonical.openPrivateOutput(output, root));
        assertEquals("keep", Files.readString(output));
    }

    @Test
    void anOutputInsideTheGenomicRootIsRefused(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Path output = root.resolve("nested").resolve("dump.txt");
        assertThrows(IOException.class, () -> DumpGenomicCanonical.openPrivateOutput(output, root));
        assertEquals(false, Files.exists(output));
    }
}
