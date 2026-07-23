package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJavaIndexedStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class BucketIndexBySample implements Serializable {
    private static final long serialVersionUID = -1230735595028630687L;
    private static final String STORAGE_FILE_NAME = "BucketIndexBySampleStorage.javabin";

    List<Integer> patientIds;
    ArrayList<String> contigSet;

    transient Logger log = LoggerFactory.getLogger(BucketIndexBySample.class);

    /**
     * Threadsafe Map of patientNum to a BigInteger which acts as a bitmask of the buckets in which each patient has a variant. The bits in
     * the BigInteger are indexed by the bucketList. To find the offset of a bucket's bit in the mask use
     * patientBucketMask.get(patientId).testBit(Collections
     */
    private FileBackedByteIndexedStorage<Integer, BigInteger> patientBucketMasks;

    /**
     * ArrayList containing all bucket keys in the dataset used as an index for the patientBucketMask offsets. This list is in natural sort
     * order so Collections.binarySearch should be used instead of indexOf when finding the offset of a given bucket.
     */
    private ArrayList<String> bucketList = new ArrayList<String>();

    public BucketIndexBySample(VariantStore variantStore, String storageDir) throws FileNotFoundException {
        log.info("Creating new Bucket Index by Sample");
        final String storageFileStr = storageDir + STORAGE_FILE_NAME;

        contigSet = new ArrayList<String>(variantStore.getVariantMaskStorage().keySet());

        // Create a bucketList, containing keys for all buckets in the variantStore
        for (String contig : contigSet) {
            FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> contigStore =
                variantStore.getVariantMaskStorage().get(contig);
            if (contigStore != null && contigStore.keys() != null) {
                bucketList.addAll(contigStore.keys().stream().map((Integer bucket) -> {
                    return contig + ":" + bucket;
                }).collect(Collectors.toList()));
                log.debug("Found " + contigStore.keys().size() + " buckets in contig " + contig);
            } else {
                log.debug("null entry for contig " + contig);
            }
        }

        // bucketList must be sorted so we can later use binarySearch to find offsets for specific buckets
        // in the patientBucketMask records
        Collections.sort(bucketList);

        log.debug("Found " + bucketList.size() + " total buckets");

        // get all patientIds as Integers, eventually this should be fixed in variantStore so they are
        // Integers to begin with, which would mean reloading all variant data everywhere so that will
        // have to wait.
        patientIds = Arrays.stream(variantStore.getPatientIds()).map(id -> {
            return Integer.parseInt(id);
        }).collect(Collectors.toList());

        // create empty char arrays for each patient
        char[][] patientBucketCharMasks = new char[patientIds.size()][bucketList.size()];
        for (int x = 0; x < patientBucketCharMasks.length; x++) {
            patientBucketCharMasks[x] = emptyBucketMaskChar();
        }
        contigSet.parallelStream().forEach((contig) -> {
            FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> contigStore =
                variantStore.getVariantMaskStorage().get(contig);
            if (contigStore != null && contigStore.keys() != null) {
                contigStore.keys().stream().forEach((Integer bucket) -> {
                    String bucketKey = contig + ":" + bucket;

                    // Create a bitmask with 1 values for each patient who has any variant in this bucket
                    VariantMask[] patientMaskForBucket = {new VariantMaskSparseImpl(Set.of())};
                    contigStore.get(bucket).values().forEach((VariableVariantMasks masks) -> {
                        if (masks.heterozygousMask != null) {
                            patientMaskForBucket[0] = patientMaskForBucket[0].union(masks.heterozygousMask);
                        }
                        // add hetreo no call bits to mask
                        if (masks.heterozygousNoCallMask != null) {
                            patientMaskForBucket[0] = patientMaskForBucket[0].union(masks.heterozygousNoCallMask);
                        }
                        if (masks.homozygousMask != null) {
                            patientMaskForBucket[0] = patientMaskForBucket[0].union(masks.homozygousMask);
                        }
                    });

                    // For each patient set the patientBucketCharMask entry to 0 or 1 if they have a variant in the bucket.
                    int indexOfBucket = Collections.binarySearch(bucketList, bucketKey) + 2; // patientBucketCharMasks has bookend bits
                    for (int x = 0; x < patientIds.size(); x++) {
                        if (patientMaskForBucket[0].testBit(x)) {
                            patientBucketCharMasks[x][indexOfBucket] = '1';
                        } else {
                            patientBucketCharMasks[x][indexOfBucket] = '0';
                        }
                    }
                });
            } else {
                log.info("null entry for contig " + contig);
            }
            log.info("completed contig " + contig);
        });

        // populate patientBucketMasks with bucketMasks for each patient
        patientBucketMasks = new FileBackedJavaIndexedStorage(Integer.class, BigInteger.class, new File(storageFileStr));

        int[] processedPatients = new int[1];
        patientIds.parallelStream().forEach((patientId) -> {
            BigInteger patientMask = new BigInteger(new String(patientBucketCharMasks[patientIds.indexOf(patientId)]), 2);
            patientBucketMasks.put(patientId, patientMask);
            processedPatients[0] += 1;
            int processedPatientsCount = processedPatients[0];
            if (processedPatientsCount % 1000 == 0) {
                log.debug("wrote " + processedPatientsCount + " patient bucket masks");
            }
        });
        patientBucketMasks.complete();
        log.info("Done creating patient bucket masks");
    }

    /**
     * Given a set of variants and a set of patientNums, filter out variants which no patients in the patientSet have any variant in the
     * bucket. This operation is extremely fast and cuts down on processing by excluding variants for queries where not all patients are
     * included.
     * 
     * @param variantSet
     * @param patientSet
     * @return
     * @throws IOException
     */
    public Set<String> filterVariantSetForPatientSet(Set<String> variantSet, Collection<Integer> patientSet) throws IOException {

        BigInteger patientBucketMask = patientSet.stream().findFirst().map(id -> patientBucketMasks.get(id))
            .orElseGet(() -> new BigInteger(new String(emptyBucketMaskChar()), 2));

        BigInteger _defaultMask = patientBucketMask;
        List<BigInteger> patientBucketmasksForSet =
            patientSet.parallelStream().map((patientNum) -> patientBucketMasks.get(patientNum)).collect(Collectors.toList());
        for (BigInteger patientMask : patientBucketmasksForSet) {
            patientBucketMask = patientMask.or(patientBucketMask);
        }

        BigInteger _bucketMask = patientBucketMask;
        int maxIndex = bucketList.size() - 1; // use to invert testBit index
        return variantSet.parallelStream().filter((variantSpec) -> {
            String bucketKey = variantSpec.split(",")[0] + ":" + (Integer.parseInt(variantSpec.split(",")[1]) / 1000);

            // testBit uses inverted indexes include +2 offset for bookends
            int bucketKeyIndex = Collections.binarySearch(bucketList, bucketKey);
            if (bucketKeyIndex < 0) {
                return false;
            }
            return _bucketMask.testBit(maxIndex - bucketKeyIndex + 2);
        }).collect(Collectors.toSet());
    }

    private char[] _emptyBucketMaskChar = null;

    /**
     * Produce an empty patientBucketMask char[] by cloning a momoized empty patientBucketMask after the first has been created.
     * 
     * @return
     */
    private char[] emptyBucketMaskChar() {
        if (_emptyBucketMaskChar == null) {
            char[] bucketMaskChar = new char[bucketList.size() + 4];
            bucketMaskChar[0] = '1';
            bucketMaskChar[1] = '1';
            bucketMaskChar[bucketMaskChar.length - 1] = '1';
            bucketMaskChar[bucketMaskChar.length - 2] = '1';
            for (int x = 2; x < bucketMaskChar.length - 2; x++) {
                bucketMaskChar[x] = '0';
            }
            _emptyBucketMaskChar = bucketMaskChar;
        }
        return _emptyBucketMaskChar.clone();
    }

    public void updateStorageDirectory(File storageDirectory) {
        patientBucketMasks.updateStorageDirectory(storageDirectory);
    }
}
