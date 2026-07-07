package edu.harvard.hms.dbmi.avillach.hpds.test.util;

import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.*;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv.CSVLoader;

import java.io.IOException;

public enum BuildIntegrationTestEnvironment {
    INSTANCE;

    private static final String PHENOTYPIC_DATA_DIRECTORY = "target/test-classes/phenotypic/";
    private static final String VCF_INDEX_FILE = "./src/test/resources/test_vcfIndex.tsv";
    private static final String STORAGE_DIR = "./target/all/";
    private static final String MERGED_DIR = "./target/merged/";

    BuildIntegrationTestEnvironment() {
        try {
            SplitChromosomeVcfLoader.main(new String[] {VCF_INDEX_FILE, STORAGE_DIR, MERGED_DIR});
            CSVLoader.main(new String[] {PHENOTYPIC_DATA_DIRECTORY});
            SplitChromosomeVariantMetadataLoader.main(new String[] {"./src/test/resources/test_vcfIndex.tsv", STORAGE_DIR});
            new GenomicDatasetFinalizer(STORAGE_DIR, 10).processDirectory();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static void main(String[] args) {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }
}
