package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.BucketIndexBySample;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class VariantService {

    private static Logger log = LoggerFactory.getLogger(VariantService.class);

    private static final Integer VARIANT_INDEX_BLOCK_SIZE = 1000000;
    private static final String VARIANT_INDEX_FBBIS_STORAGE_FILE = "/opt/local/hpds/all/variantIndex_fbbis_storage.javabin";
    private static final String VARIANT_INDEX_FBBIS_FILE = "/opt/local/hpds/all/variantIndex_fbbis.javabin";
    private static final String BUCKET_INDEX_BY_SAMPLE_FILE = "/opt/local/hpds/all/BucketIndexBySample.javabin";


    private final VariantStore variantStore;

    // why is this not VariantSpec[]?
    private String[] variantIndex = null;
    private BucketIndexBySample bucketIndex;

    public String[] getVariantIndex() {
        return variantIndex;
    }

    public BucketIndexBySample getBucketIndex() {
        return bucketIndex;
    }
    public Collection<String> filterVariantSetForPatientSet(Set<String> variantSet, List<Integer> patientSet) {
        try {
            return bucketIndex.filterVariantSetForPatientSet(variantSet, patientSet);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public VariantService() throws IOException, ClassNotFoundException, InterruptedException {
        variantStore = VariantStore.deserializeInstance();
        try {
            loadGenomicCacheFiles();
        } catch (Exception e) {
            log.error("Failed to load genomic data: " + e.getLocalizedMessage(), e);
        }
    }

    public void populateVariantIndex() throws InterruptedException {
        //skip if we have no variants
        if(variantStore.getPatientIds().length == 0) {
            variantIndex = new String[0];
            log.warn("No Genomic Data found.  Skipping variant Indexing");
            return;
        }
        int[] numVariants = {0};
        HashMap<String, String[]> contigMap = new HashMap<>();

        ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        variantStore.getVariantMaskStorage().entrySet().forEach(entry->{
            ex.submit(()->{
                int numVariantsInContig = 0;
                FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariantMasks>> storage = entry.getValue();
                HashMap<Integer, String[]> bucketMap = new HashMap<>();
                log.info("Creating bucketMap for contig " + entry.getKey());
                for(Integer bucket: storage.keys()){
                    try {
                        ConcurrentHashMap<String, VariantMasks> bucketStorage = storage.get(bucket);
                        numVariantsInContig += bucketStorage.size();
                        bucketMap.put(bucket, bucketStorage.keySet().toArray(new String[0]));
                    } catch (IOException e) {
                        log.error("an error occurred", e);
                    }
                };
                log.info("Completed bucketMap for contig " + entry.getKey());
                String[] variantsInContig = new String[numVariantsInContig];
                int current = 0;
                for(String[] bucketList  : bucketMap.values()) {
                    System.arraycopy(bucketList, 0, variantsInContig, current, bucketList.length);
                    current = current + bucketList.length;
                }
                bucketMap.clear();
                synchronized(numVariants) {
                    log.info("Found " + variantsInContig.length + " variants in contig " + entry.getKey() + ".");
                    contigMap.put(entry.getKey(), variantsInContig);
                    numVariants[0] += numVariantsInContig;
                }
            });
        });
        ex.shutdown();
        while(!ex.awaitTermination(10, TimeUnit.SECONDS)) {
            Thread.sleep(20000);
            log.info("Awaiting completion of variant index");
        }

        log.info("Found " + numVariants[0] + " total variants.");

        variantIndex = new String[numVariants[0]];

        int current = 0;
        for(String[] contigList  : contigMap.values()) {
            System.arraycopy(contigList, 0, variantIndex, current, contigList.length);
            current = current + contigList.length;
        }
        contigMap.clear();

        Arrays.sort(variantIndex);
        log.info("Index created with " + variantIndex.length + " total variants.");
    }

    /**
     * This process takes a while (even after the cache is built), so let's spin it out into it's own thread. (not done yet)
     * @throws FileNotFoundException
     * @throws IOException
     * @throws InterruptedException
     */
    private void loadGenomicCacheFiles() throws FileNotFoundException, IOException, InterruptedException {
        if(bucketIndex==null) {
            if(variantIndex==null) {
                if(!new File(VARIANT_INDEX_FBBIS_FILE).exists()) {
                    log.info("Creating new " + VARIANT_INDEX_FBBIS_FILE);
                    populateVariantIndex();
                    FileBackedByteIndexedStorage<Integer, String[]> fbbis =
                            new FileBackedByteIndexedStorage<Integer, String[]>(Integer.class, String[].class, new File(VARIANT_INDEX_FBBIS_STORAGE_FILE));
                    try (ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(VARIANT_INDEX_FBBIS_FILE)));
                    ){

                        log.info("Writing Cache Object in blocks of " + VARIANT_INDEX_BLOCK_SIZE);

                        int bucketCount = (variantIndex.length / VARIANT_INDEX_BLOCK_SIZE) + 1;  //need to handle overflow
                        int index = 0;
                        for( int i = 0; i < bucketCount; i++) {
                            int blockSize = i == (bucketCount - 1) ? (variantIndex.length % VARIANT_INDEX_BLOCK_SIZE) : VARIANT_INDEX_BLOCK_SIZE;

                            String[] variantArrayBlock = new String[blockSize];
                            System.arraycopy(variantIndex, index, variantArrayBlock, 0, blockSize);
                            fbbis.put(i, variantArrayBlock);

                            index += blockSize;
                            log.info("saved " + index + " variants");
                        }
                        fbbis.complete();
                        oos.writeObject("" + variantIndex.length);
                        oos.writeObject(fbbis);
                        oos.flush();oos.close();
                    }
                }else {
                    ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                    try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(VARIANT_INDEX_FBBIS_FILE)));){
                        Integer variantCount = Integer.parseInt((String) objectInputStream.readObject());
                        FileBackedByteIndexedStorage<Integer, String[]> indexStore = (FileBackedByteIndexedStorage<Integer, String[]>) objectInputStream.readObject();
                        log.info("loading " + VARIANT_INDEX_FBBIS_FILE);

                        variantIndex = new String[variantCount];
                        String[] _varaiantIndex2 = variantIndex;

                        //variant index has to be a single array (we use a binary search for lookups)
                        //but reading/writing to disk should be batched for performance
                        int bucketCount = (variantCount / VARIANT_INDEX_BLOCK_SIZE) + 1;  //need to handle overflow

                        for( int i = 0; i < bucketCount; i++) {
                            final int _i = i;
                            ex.submit(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        String[] variantIndexBucket = indexStore.get(_i);
                                        System.arraycopy(variantIndexBucket, 0, _varaiantIndex2, (_i * VARIANT_INDEX_BLOCK_SIZE), variantIndexBucket.length);
                                        log.info("loaded " + (_i * VARIANT_INDEX_BLOCK_SIZE) + " block");
                                    } catch (IOException e) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace();
                                    }
                                }
                            });
                        }
                        objectInputStream.close();
                        ex.shutdown();
                        while(! ex.awaitTermination(60, TimeUnit.SECONDS)) {
                            System.out.println("Waiting for tasks to complete");
                            Thread.sleep(10000);
                        }
                    } catch (IOException | ClassNotFoundException | NumberFormatException e) {
                        log.error("an error occurred", e);
                    }
                    log.info("Found " + variantIndex.length + " total variants.");
                }
            }
            if(variantStore.getPatientIds().length > 0 && !new File(BUCKET_INDEX_BY_SAMPLE_FILE).exists()) {
                log.info("creating new " + BUCKET_INDEX_BY_SAMPLE_FILE);
                bucketIndex = new BucketIndexBySample(variantStore);
                try (
                        FileOutputStream fos = new FileOutputStream(BUCKET_INDEX_BY_SAMPLE_FILE);
                        GZIPOutputStream gzos = new GZIPOutputStream(fos);
                        ObjectOutputStream oos = new ObjectOutputStream(gzos);
                ){
                    oos.writeObject(bucketIndex);
                    oos.flush();oos.close();
                }
            }else {
                try (ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(BUCKET_INDEX_BY_SAMPLE_FILE)));){
                    log.info("loading " + BUCKET_INDEX_BY_SAMPLE_FILE);
                    bucketIndex = (BucketIndexBySample) objectInputStream.readObject();
                    objectInputStream.close();
                } catch (IOException | ClassNotFoundException e) {
                    log.error("an error occurred", e);
                }
            }
        }
    }

    public String[] getPatientIds() {
        return variantStore.getPatientIds();
    }

    public VariantMasks getMasks(String variantName, VariantBucketHolder<VariantMasks> bucketCache) {
        try {
            return variantStore.getMasks(variantName, bucketCache);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public BigInteger emptyBitmask() {
        return variantStore.emptyBitmask();
    }
}
