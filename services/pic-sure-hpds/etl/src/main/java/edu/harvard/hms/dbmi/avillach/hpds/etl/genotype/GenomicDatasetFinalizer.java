package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.BucketIndexBySample;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;

import java.io.IOException;

public class GenomicDatasetFinalizer {
    private static String genomicDirectory;

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length != 2) {
            throw new IllegalArgumentException("2 arguments must be provided: source directory 1, source directory 2, output directory");
        }
        genomicDirectory = args[0];
        String outputDirectory = args[1];

        VariantStore variantStore = VariantStore.readInstance(genomicDirectory);
        BucketIndexBySample bucketIndexBySample = new BucketIndexBySample(variantStore, genomicDirectory);
    }
}
