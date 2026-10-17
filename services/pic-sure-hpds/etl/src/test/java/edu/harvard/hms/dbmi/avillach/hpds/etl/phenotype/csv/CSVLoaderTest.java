package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CSVLoaderTest {

    @Test
    void shouldStripSuffixFromStudyConsent() {
        Assertions.assertEquals("phs000000.c1", CSVLoader.partitionNameFor("phs000000.c1-allConcepts.csv"));
    }

    @Test
    void shouldKeepHyphenatedConsent() {
        Assertions.assertEquals("tutorial-biolincc_camp", CSVLoader.partitionNameFor("tutorial-biolincc_camp-allConcepts.csv"));
        Assertions.assertEquals("open_access-1000Genomes", CSVLoader.partitionNameFor("open_access-1000Genomes-allConcepts.csv"));
    }

    @Test
    void shouldFallBackToTextBeforeFirstHyphenWithoutSuffix() {
        Assertions.assertEquals("phs999999.c2", CSVLoader.partitionNameFor("phs999999.c2-other_allConcepts.csv"));
        Assertions.assertEquals("allConcepts.csv", CSVLoader.partitionNameFor("allConcepts.csv"));
    }

    @Test
    void shouldKeepLegacyNameForBareSuffix() {
        Assertions.assertEquals("", CSVLoader.partitionNameFor("-allConcepts.csv"));
    }
}
