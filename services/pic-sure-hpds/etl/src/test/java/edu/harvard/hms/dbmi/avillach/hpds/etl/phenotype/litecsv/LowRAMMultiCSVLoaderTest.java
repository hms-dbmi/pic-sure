package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

class LowRAMMultiCSVLoaderTest {

    @Test
    void shouldFilterOutNonCSVs(@TempDir File testDir) throws IOException {
        Path csvPath = Path.of(testDir.getAbsolutePath(), "test.txt");
        RandomAccessFile largeFile = new RandomAccessFile(csvPath.toString(), "rw");
        largeFile.setLength(6L*1024);

        LowRAMCSVProcessor processor = Mockito.mock(LowRAMCSVProcessor.class);
        LowRAMLoadingStore store = Mockito.mock(LowRAMLoadingStore.class);

        LowRAMMultiCSVLoader subject = new LowRAMMultiCSVLoader(store, processor, testDir.getAbsolutePath());
        int actual = subject.processCSVsFromHPDSDir();

        Assertions.assertEquals(0, actual);
        Mockito.verify(processor, Mockito.times(0)).process(Mockito.any());
    }

    @Test
    void shouldProcessSmallCSVs(@TempDir File testDir) throws IOException {
        Path csvPath = Path.of(testDir.getAbsolutePath(), "test.csv");
        RandomAccessFile largeFile = new RandomAccessFile(csvPath.toString(), "rw");
        largeFile.setLength(6L*1024);

        LowRAMCSVProcessor processor = Mockito.mock(LowRAMCSVProcessor.class);
        Mockito.when(processor.process(Mockito.any()))
            .thenReturn(new IngestStatus(csvPath, 10, 10, 10L));
        LowRAMLoadingStore store = Mockito.mock(LowRAMLoadingStore.class);

        LowRAMMultiCSVLoader subject = new LowRAMMultiCSVLoader(store, processor, testDir.getAbsolutePath());
        int actual = subject.processCSVsFromHPDSDir();

        Assertions.assertEquals(0, actual);
        Mockito.verify(processor, Mockito.times(1)).process(Mockito.any());
    }
}