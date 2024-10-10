package edu.harvard.hms.dbmi.avillach.hpds.test.util;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.BucketIndexBySample;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.NewVCFLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.VariantMetadataLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.CSVLoader;

import java.io.IOException;

public enum BuildIntegrationTestEnvironment {
    INSTANCE;

    private static final String PHENOTYPIC_DATA_DIRECTORY = "target/test-classes/phenotypic/";
    private final String VCF_INDEX_FILE = "./src/test/resources/test_vcfIndex.tsv";
    private final String STORAGE_DIR = "./target/";
    private final String MERGED_DIR = "./target/merged/";

    public String binFile = "target/VariantMetadata.javabin";

    BuildIntegrationTestEnvironment() {
        try {
            NewVCFLoader.main(new String[] {VCF_INDEX_FILE, STORAGE_DIR, MERGED_DIR});
            CSVLoader.main(new String[] {PHENOTYPIC_DATA_DIRECTORY});
            VariantMetadataLoader.main(new String[] {"./src/test/resources/test_vcfIndex.tsv", binFile, "target/VariantMetadataStorage.bin"});
            VariantStore variantStore = VariantStore.readInstance(STORAGE_DIR);
            BucketIndexBySample bucketIndexBySample = new BucketIndexBySample(variantStore, STORAGE_DIR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    public static void main(String[] args) {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }
}
