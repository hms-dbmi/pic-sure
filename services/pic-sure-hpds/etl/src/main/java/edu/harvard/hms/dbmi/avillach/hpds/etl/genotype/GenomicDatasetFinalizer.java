package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.BucketIndexBySample;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public class GenomicDatasetFinalizer {

    private static final Logger log = LoggerFactory.getLogger(GenomicDatasetFinalizer.class);
    private static final String genomicDirectory = "/opt/local/hpds/all/";
    private final File[] chromosomeDirectories;
    private final Semaphore sem;


    public static void main(String[] args) throws IOException, ClassNotFoundException, InterruptedException {
        new GenomicDatasetFinalizer(genomicDirectory, 10).processDirectory();
    }

    public GenomicDatasetFinalizer(String genomicDirectory, int maxThreads) {
        chromosomeDirectories = new File(genomicDirectory).listFiles(File::isDirectory);
        if (chromosomeDirectories == null) {
            throw new IllegalArgumentException("Path " + genomicDirectory + " does not contain any directories");
        }
        if (chromosomeDirectories.length > 50) {
            throw new IllegalArgumentException(
                "Number of chromosome partitions exceeds maximum of 50 (" + chromosomeDirectories.length + ")"
            );
        }
        sem = new Semaphore(maxThreads);
    }


    public void processDirectory() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (File chromosomeDirectory : chromosomeDirectories) {
                sem.acquire();
                executor.submit(() -> {
                    try {
                        String bucketIndexDirectory = chromosomeDirectory.getPath() + "/";
                        VariantStore variantStore = VariantStore.readInstance(bucketIndexDirectory);
                        BucketIndexBySample bucketIndexBySample = new BucketIndexBySample(variantStore, bucketIndexDirectory);
                        String bucketIndexFilename = bucketIndexDirectory + "BucketIndexBySample.javabin";
                        log.info("creating new " + bucketIndexFilename);
                        try (
                            FileOutputStream fos = new FileOutputStream(bucketIndexFilename); GZIPOutputStream gzos =
                                new GZIPOutputStream(fos); ObjectOutputStream oos = new ObjectOutputStream(gzos);
                        ) {
                            oos.writeObject(bucketIndexBySample);
                            oos.flush();
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        executor.shutdown();
                        throw new RuntimeException(e);
                    }
                    sem.release();
                });
            }
            executor.shutdown();
            while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                log.info("Awaiting threadpool termination");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
