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

    @Test
    void aSymlinkedParentWithDotDotCannotReachIntoTheRoot(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectory(dir.resolve("root"));
        Path inner = Files.createDirectory(root.resolve("inner"));
        Path link = Files.createSymbolicLink(dir.resolve("s"), inner);
        Path output = link.resolve("..").resolve("z.txt");
        assertThrows(IOException.class, () -> DumpGenomicCanonical.openPrivateOutput(output, root));
        assertEquals(false, Files.exists(root.resolve("z.txt")));
    }

    @Test
    void aSymlinkedParentIntoTheRootIsRefused(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectory(dir.resolve("root"));
        Path link = Files.createSymbolicLink(dir.resolve("s"), root);
        assertThrows(IOException.class, () -> DumpGenomicCanonical.openPrivateOutput(link.resolve("z.txt"), root));
        assertEquals(false, Files.exists(root.resolve("z.txt")));
    }

    @Test
    void aDanglingSymlinkAtTheOutputPathIsRefusedAndNotFollowed(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectory(dir.resolve("root"));
        Path target = dir.resolve("elsewhere.txt");
        Path output = Files.createSymbolicLink(dir.resolve("dump.txt"), target);
        assertThrows(IOException.class, () -> DumpGenomicCanonical.openPrivateOutput(output, root));
        assertEquals(false, Files.exists(target));
    }

    @Test
    void aFileNameOfDotOrDotDotIsRefused(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        assertThrows(IOException.class, () -> DumpGenomicCanonical.resolveOutput(dir.resolve("."), root));
        assertThrows(IOException.class, () -> DumpGenomicCanonical.resolveOutput(dir.resolve(".."), root));
    }

    @Test
    void anUnknownFormOrModeIsRefusedBeforeAnyOutputExists(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Path output = dir.resolve("dump.txt");
        assertThrows(
            IllegalArgumentException.class,
            () -> DumpGenomicCanonical.main(new String[] {"local", root.toString(), output.toString(), "everything"})
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DumpGenomicCanonical.main(new String[] {"bogus", root.toString(), output.toString(), "rows"})
        );
        assertEquals(false, Files.exists(output));
    }

    @Test
    void aFailedDumpDeletesItsOutput(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Path output = dir.resolve("dump.txt");
        assertThrows(Exception.class, () -> DumpGenomicCanonical.main(new String[] {"local", root.toString(), output.toString()}));
        assertEquals(false, Files.exists(output));
    }

    @Test
    void anExistingOutputIsLeftIntactWhenTheRunIsRefused(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Path output = Files.writeString(dir.resolve("dump.txt"), "another process");
        assertThrows(IOException.class, () -> DumpGenomicCanonical.main(new String[] {"local", root.toString(), output.toString()}));
        assertEquals("another process", Files.readString(output));
    }
}
