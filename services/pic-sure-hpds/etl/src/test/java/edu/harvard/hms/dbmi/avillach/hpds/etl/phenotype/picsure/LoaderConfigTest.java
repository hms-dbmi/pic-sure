package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoaderConfigTest {

    @Test
    void defaultsMatchTheIngestServiceDefaults() {
        LoaderConfig config = LoaderConfig.defaults();

        assertNull(config.spoolDir());
        assertEquals(10_000, config.batchSize());
        assertEquals(16, config.storeCacheSize());
        assertEquals(12, config.fileProcessingThreads());
        assertEquals(0, config.maxObservationsPerConcept(), "unlimited by default");
        assertFalse(config.dedup());
        assertTrue(config.failFast());
        assertFalse(config.overwrite());
        assertEquals(12, config.finalizationConcurrency());
        assertEquals(1000, config.finalizationChunkSize());
    }

    @Test
    void propertiesOverrideDefaults() {
        Map<String, String> properties = Map.of(
            "picsure.loader.spoolDir", "/mnt/nvme/spool", "picsure.loader.batchSize", "500", "picsure.loader.storeCacheSize", "64",
            "picsure.loader.fileProcessingThreads", " 4 ", "picsure.loader.maxObservationsPerConcept", "2000000",
            "picsure.loader.finalizationConcurrency", "8", "picsure.loader.finalizationChunkSize", "250"
        );

        LoaderConfig config = LoaderConfig.fromProperties(properties::get);

        assertEquals(Path.of("/mnt/nvme/spool"), config.spoolDir());
        assertEquals(500, config.batchSize());
        assertEquals(64, config.storeCacheSize());
        assertEquals(4, config.fileProcessingThreads());
        assertEquals(2_000_000, config.maxObservationsPerConcept());
        assertEquals(8, config.finalizationConcurrency());
        assertEquals(250, config.finalizationChunkSize());
    }

    @Test
    void blankValuesFallBackToDefaults() {
        LoaderConfig config = LoaderConfig.fromProperties(key -> "   ");

        assertEquals(LoaderConfig.defaults(), config);
    }

    @Test
    void readsSystemProperties() {
        System.setProperty("picsure.loader.batchSize", "123");
        try {
            assertEquals(123, LoaderConfig.fromSystemProperties().batchSize());
        } finally {
            System.clearProperty("picsure.loader.batchSize");
        }
    }

    @Test
    void rejectsNonNumericAndNonPositiveValues() {
        IllegalArgumentException notANumber = assertThrows(
            IllegalArgumentException.class, () -> LoaderConfig.fromProperties(Map.of("picsure.loader.batchSize", "lots")::get)
        );
        assertTrue(notANumber.getMessage().contains("picsure.loader.batchSize"));
        assertThrows(IllegalArgumentException.class, () -> LoaderConfig.fromProperties(Map.of("picsure.loader.storeCacheSize", "0")::get));
        assertThrows(
            IllegalArgumentException.class, () -> LoaderConfig.fromProperties(Map.of("picsure.loader.finalizationChunkSize", "-5")::get)
        );
    }
}
