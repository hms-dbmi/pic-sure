package edu.harvard.hms.dbmi.avillach.hpds.test.util;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.BucketIndexBySample;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.NewVCFLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.SplitChromosomeVariantMetadataLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.SplitChromosomeVcfLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.VariantMetadataLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv.CSVLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

public enum BuildIntegrationTestEnvironment {
    INSTANCE;

    private static final String PHENOTYPIC_DATA_DIRECTORY = "target/test-classes/phenotypic/";
    private final String VCF_INDEX_FILE = "./src/test/resources/test_vcfIndex.tsv";
    private final String STORAGE_DIR = "./target/all/";
    private final String MERGED_DIR = "./target/merged/";

    public String binFile = "target/VariantMetadata.javabin";

    BuildIntegrationTestEnvironment() {
        try {
            SplitChromosomeVcfLoader.main(new String[] {VCF_INDEX_FILE, STORAGE_DIR, MERGED_DIR});
            CSVLoader.main(new String[] {PHENOTYPIC_DATA_DIRECTORY});
            SplitChromosomeVariantMetadataLoader.main(new String[] {"./src/test/resources/test_vcfIndex.tsv", STORAGE_DIR});
            List.of("chr4", "chr20", "chr21").forEach(dir -> {
                VariantStore variantStore = null;
                try {
                    variantStore = VariantStore.readInstance(STORAGE_DIR + dir + "/");
                    BucketIndexBySample bucketIndexBySample = new BucketIndexBySample(variantStore, STORAGE_DIR + dir + "/");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static void main(String[] args) {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }
}
