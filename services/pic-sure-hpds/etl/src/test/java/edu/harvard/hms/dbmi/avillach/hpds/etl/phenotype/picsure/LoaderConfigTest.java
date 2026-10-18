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
        assertEquals(0, config.maxRejectedRows());
        assertEquals(0, config.maxTimestampFailures());
        assertEquals(0, config.chunkConcurrency());
        assertTrue(config.effectiveChunkConcurrency() >= 1);
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

    @Test
    void chunkConcurrencyIsBoundedByTheCoresAndHalfTheStoreCache() {
        LoaderConfig tinyCache = LoaderConfig.fromProperties(Map.of("picsure.loader.storeCacheSize", "2")::get);
        assertEquals(1, tinyCache.effectiveChunkConcurrency());
        LoaderConfig oneEntry = LoaderConfig.fromProperties(Map.of("picsure.loader.storeCacheSize", "1")::get);
        assertEquals(1, oneEntry.effectiveChunkConcurrency(), "at least one");
        LoaderConfig bigCache = LoaderConfig.fromProperties(Map.of("picsure.loader.storeCacheSize", "100000")::get);
        assertEquals(Runtime.getRuntime().availableProcessors(), bigCache.effectiveChunkConcurrency());
        LoaderConfig explicit = LoaderConfig.fromProperties(Map.of("picsure.loader.chunkConcurrency", "3")::get);
        assertEquals(3, explicit.effectiveChunkConcurrency());
    }

    @Test
    void dataQualityLimitsCanBeSetAndMayNotBeNegative() {
        LoaderConfig config =
            LoaderConfig.fromProperties(Map.of("picsure.loader.maxRejectedRows", "7", "picsure.loader.maxTimestampFailures", "3")::get);
        assertEquals(7, config.maxRejectedRows());
        assertEquals(3, config.maxTimestampFailures());
        assertThrows(
            IllegalArgumentException.class, () -> LoaderConfig.fromProperties(Map.of("picsure.loader.maxRejectedRows", "-1")::get)
        );
        assertThrows(
            IllegalArgumentException.class, () -> LoaderConfig.fromProperties(Map.of("picsure.loader.chunkConcurrency", "-2")::get)
        );
    }

    @Test
    void inputAndPartitionByAreOptional() {
        LoaderConfig defaults = LoaderConfig.defaults();
        assertFalse(defaults.combinedInput());
        assertNull(defaults.input());
        assertNull(defaults.partitionBy());

        LoaderConfig combined = LoaderConfig.fromProperties(Map.of("picsure.loader.input", " /data/allConcepts_data_analyzer.csv ")::get);
        assertTrue(combined.combinedInput());
        assertEquals(java.nio.file.Path.of("/data/allConcepts_data_analyzer.csv"), combined.input());
        assertEquals("consent", combined.effectivePartitionBy(), "consent is the default for a combined input");

        LoaderConfig none =
            LoaderConfig.fromProperties(Map.of("picsure.loader.input", "a.csv", "picsure.loader.partitionBy", " NONE ")::get);
        assertEquals("none", none.partitionBy());
        assertEquals("none", none.effectivePartitionBy());
    }

    @Test
    void partitionByMustBeConsentOrNone() {
        assertThrows(IllegalArgumentException.class, () -> LoaderConfig.fromProperties(Map.of("picsure.loader.partitionBy", "study")::get));
    }
}
