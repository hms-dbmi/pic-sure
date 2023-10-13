package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.*;
import edu.harvard.hms.dbmi.avillach.hpds.data.storage.FileBackedStorageVariantMasksImpl;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJsonIndexStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GenomicDatasetMerger {

    private static Logger log = LoggerFactory.getLogger(GenomicDatasetMerger.class);

    private final VariantStore variantStore1;
    private final VariantStore variantStore2;

    private final Map<String, FileBackedByteIndexedInfoStore> infoStores1;
    private final Map<String, FileBackedByteIndexedInfoStore> infoStores2;

    private final String outputDirectory;

    private final VariantStore mergedVariantStore;

    public GenomicDatasetMerger(VariantStore variantStore1, VariantStore variantStore2, Map<String, FileBackedByteIndexedInfoStore> infoStores1, Map<String, FileBackedByteIndexedInfoStore> infoStores2, String outputDirectory) {
        this.variantStore1 = variantStore1;
        this.variantStore2 = variantStore2;
        this.mergedVariantStore = new VariantStore();
        this.infoStores1 = infoStores1;
        this.infoStores2 = infoStores2;
        this.outputDirectory = outputDirectory;
        validate();
    }

    private void validate() {
        if (!variantStore1.getVariantMaskStorage().keySet().equals(variantStore2.getVariantMaskStorage().keySet())) {
            log.error("Variant store chromosomes do not match:");
            log.error(String.join(", ", variantStore1.getVariantMaskStorage().keySet()));
            log.error(String.join(", ", variantStore2.getVariantMaskStorage().keySet()));
            throw new IllegalStateException("Unable to merge variant stores with different numbers of chromosomes");
        }
        Sets.SetView<String> patientIntersection = Sets.intersection(Sets.newHashSet(variantStore1.getPatientIds()), Sets.newHashSet(variantStore2.getPatientIds()));
        if (!patientIntersection.isEmpty()) {
            throw new IllegalStateException("Cannot merge genomic datasets containing the same patient id");
        }
    }

    public VariantStore mergeVariantStore(Map<String, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>>> mergedChromosomeMasks) {
        mergedVariantStore.setVariantMaskStorage(mergedChromosomeMasks);
        mergedVariantStore.setPatientIds(mergePatientIds());
        return mergedVariantStore;
    }

    /**
     * Since both sets of variant indexes reference a different variant spec list, we need to re-index
     * the values in the second set of variant indexes.
     *
     * For each variant in the second list of variants:
     * If it exists in list one, update any references to it by index to it's index in list 1
     * Otherwise, append it to list one, and update any references to it by index to it's new position in list 1
     *
     * Ex:
     *
     * variantIndex1 = ["chr1,1000,A,G", "chr1,1002,A,G", "chr1,2000,A,G"]
     * variantIndex2 = ["chr1,1000,A,G", "chr1,1004,A,G", "chr1,3000,A,G"]
     *
     * GeneWithVariant_store1 = [0, 1]
     * GeneWithVariant_store2 = [0, 1, 2]
     *
     * mergedVariantIndex = ["chr1,1000,A,G", "chr1,1002,A,G", "chr1,2000,A,G", "chr1,1004,A,G", "chr1,3000,A,G"]
     * GeneWithVariant_merged = [0, 1, 3, 4]
     *
     * @return
     * @throws IOException
     */
    public Map<String, FileBackedByteIndexedInfoStore> mergeVariantIndexes() throws IOException {
        String[] variantIndex1 = variantStore1.getVariantSpecIndex();
        String[] variantIndex2 = variantStore2.getVariantSpecIndex();

        Map<String, Integer> variantSpecToIndexMap = new HashMap<>();
        LinkedList<String> variantSpecList = new LinkedList<>(Arrays.asList(variantIndex1));
        for (int i = 0; i < variantIndex1.length; i++) {
            variantSpecToIndexMap.put(variantIndex1[i], i);
        }

        // Will contain any re-mapped indexes in the second variant index. For example, if a variant is contained in both
        // data sets, the merged data set will use the index from dataset 1 to reference it, and any references in data
        // set 2 for this variant needs to be re-mapped. Likewise, if a variant in set 2 is new, it will be appended to
        // the list and also need to be re-mapped
        Integer[] remappedIndexes = new Integer[variantIndex2.length];

        for (int i = 0; i < variantIndex2.length; i++) {
            String variantSpec = variantIndex2[i];
            Integer variantIndex = variantSpecToIndexMap.get(variantSpec);
            if (variantIndex != null) {
                remappedIndexes[i] = variantIndex;
            } else {
                variantSpecList.add(variantSpec);
                // the new index is the now last item in the list
                int newVariantSpecIndex = variantSpecList.size() - 1;
                remappedIndexes[i] = newVariantSpecIndex;
                variantSpecToIndexMap.put(variantSpec, newVariantSpecIndex);
            }
        }

        Map<String, FileBackedByteIndexedInfoStore> mergedInfoStores = new HashMap<>();

        if (!infoStores1.keySet().equals(infoStores2.keySet())) {
            throw new IllegalStateException("Info stores do not match");
        }
        for (Map.Entry<String, FileBackedByteIndexedInfoStore> infoStores1Entry : infoStores1.entrySet()) {
            FileBackedByteIndexedInfoStore infoStore2 = infoStores2.get(infoStores1Entry.getKey());

            FileBackedByteIndexedStorage<String, Integer[]> allValuesStore1 = infoStores1Entry.getValue().getAllValues();
            FileBackedByteIndexedStorage<String, Integer[]> allValuesStore2 = infoStore2.getAllValues();
            ConcurrentHashMap<String, ConcurrentSkipListSet<Integer>> mergedInfoStoreValues = new ConcurrentHashMap<>();

            Sets.SetView<String> allKeys = Sets.union(allValuesStore1.keys(), allValuesStore2.keys());
            for (String key : allKeys) {
                Set<Integer> store1Values = Set.of(allValuesStore1.getOrELse(key, new Integer[]{}));
                Set<Integer> store2Values = Set.of(allValuesStore2.getOrELse(key, new Integer[]{}));
                Set<Integer> remappedValuesStore2 = store2Values.stream().map(value -> remappedIndexes[value]).collect(Collectors.toSet());

                Set<Integer> mergedValues = Sets.union(store1Values, remappedValuesStore2);
                mergedInfoStoreValues.put(key, new ConcurrentSkipListSet<>(mergedValues));
            }

            InfoStore infoStore = new InfoStore(infoStore2.description, null, infoStores1Entry.getKey());
            infoStore.allValues = mergedInfoStoreValues;
            FileBackedByteIndexedInfoStore mergedStore = new FileBackedByteIndexedInfoStore(new File(outputDirectory), infoStore);
            mergedInfoStores.put(infoStores1Entry.getKey(), mergedStore);
        }

        mergedVariantStore.setVariantSpecIndex(variantSpecList.toArray(new String[0]));
        return mergedInfoStores;
    }

    /**
     * Merge patient ids from both variant stores. We are simply appending patients from store 2 to patients from store 1
     *
     * @return the merged patient ids
     */
    private String[] mergePatientIds() {
        return Stream.concat(Arrays.stream(variantStore1.getPatientIds()), Arrays.stream(variantStore2.getPatientIds()))
                .toArray(String[]::new);
    }

    /**
     * For each chromosome, call mergeChromosomeMask to merge the masks
     * @return
     */
    public Map<String, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>>> mergeChromosomeMasks() {
        Map<String, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>>> mergedMaskStorage = new ConcurrentHashMap<>();
        variantStore1.getVariantMaskStorage().keySet().parallelStream().forEach(chromosome -> {
            try {
                mergedMaskStorage.put(chromosome, mergeChromosomeMask(chromosome));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        });
        return mergedMaskStorage;
    }

    /**
     * Merge the masks for a chormosome. The logic here is somewhat complex but straightforward, dealing with no values at various places in the maps.
     * If a variant mask contains no matches (ie, is 000000...), it is not stored in the data.
     *
     * Examples:
     * variantMaskStorage1: {
     *     10001 -> {
     *         "chr22,10001031,A,G" -> "10101010",
     *         "chr22,10001143,G,A" -> "10101010"
     *     },
     *     10002 -> {
     *         "chr22,10002031,A,G" -> "10101010",
     *         "chr22,10002143,G,A" -> "10101010"
     *     }
     * }
     * variantMaskStorage2: {
     *     10001 -> {
     *         "chr22,10001031,A,G" -> "00001111",
     *         "chr22,10001213,A,G" -> "00001111"
     *     },
     *     10003 -> {
     *         "chr22,10003031,A,G" -> "00001111",
     *         "chr22,10003213,A,G" -> "00001111"
     *     }
     * }
     *
     * mergedVariantMaskStorage: {
     *     10001 -> {
     *         "chr22,10001031,A,G" -> "1010101000001111",
     *         "chr22,10001213,A,G" -> "0000000000001111",
     *         "chr22,10001143,G,A" -> "1010101000000000"
     *     },
     *     10002 -> {
     *         "chr22,10002031,A,G" -> "1010101000000000",
     *         "chr22,10002143,G,A" -> "1010101000000000"
     *     }
     *     10003 -> {
     *         "chr22,10003031,A,G" -> "0000000000001111",
     *         "chr22,10003213,A,G" -> "0000000000001111"
     *     }
     * }
     */
    public FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>> mergeChromosomeMask(String chromosome) throws FileNotFoundException {
        FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>> variantMaskStorage1 = variantStore1.getVariantMaskStorage().get(chromosome);
        FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>> variantMaskStorage2 = variantStore2.getVariantMaskStorage().get(chromosome);

        FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariantMasks>> merged = new FileBackedStorageVariantMasksImpl(new File(outputDirectory + chromosome + "masks.bin"));
        variantMaskStorage1.keys().forEach(key -> {
            Map<String, VariantMasks> masks1 = variantMaskStorage1.get(key);
            Map<String, VariantMasks> masks2 = variantMaskStorage2.get(key);
            if (masks2 == null) {
                masks2 = Map.of();
            }

            ConcurrentHashMap<String, VariantMasks> mergedMasks = new ConcurrentHashMap<>();
            for (Map.Entry<String, VariantMasks> entry : masks1.entrySet()) {
                VariantMasks variantMasks2 = masks2.get(entry.getKey());
                if (variantMasks2 == null) {
                    // this will have all null masks, which will result in null when
                    // appended to a null, or be replaced with an empty bitmask otherwise
                    variantMasks2 = new VariantMasks();
                }
                mergedMasks.put(entry.getKey(), append(entry.getValue(), variantMasks2));
            }
            // Any entry in the second set that is not in the merged set can be merged with an empty variant mask,
            // if there were a corresponding entry in set 1, it would have been merged in the previous loop
            for (Map.Entry<String, VariantMasks> entry : masks2.entrySet()) {
                if (!mergedMasks.containsKey(entry.getKey())) {
                    mergedMasks.put(entry.getKey(), append(new VariantMasks(), entry.getValue()));
                }
            }
            merged.put(key, mergedMasks);
        });

        ConcurrentHashMap<String, VariantMasks> mergedMasks = new ConcurrentHashMap<>();
        variantMaskStorage2.keys().forEach(key -> {
            Map<String, VariantMasks> masks2 = variantMaskStorage2.get(key);
            for (Map.Entry<String, VariantMasks> entry : masks2.entrySet()) {
                if (!mergedMasks.containsKey(entry.getKey())) {
                    mergedMasks.put(entry.getKey(), append(new VariantMasks(), entry.getValue()));
                }
            }
        });
        return merged;
    }

    public VariantMasks append(VariantMasks variantMasks1, VariantMasks variantMasks2) {
        VariantMasks appendedMasks = new VariantMasks();
        appendedMasks.homozygousMask = appendMask(variantMasks1.homozygousMask, variantMasks2.homozygousMask);
        appendedMasks.heterozygousMask = appendMask(variantMasks1.heterozygousMask, variantMasks2.heterozygousMask);
        appendedMasks.homozygousNoCallMask = appendMask(variantMasks1.homozygousNoCallMask, variantMasks2.homozygousNoCallMask);
        appendedMasks.heterozygousNoCallMask = appendMask(variantMasks1.heterozygousNoCallMask, variantMasks2.heterozygousNoCallMask);
        return appendedMasks;
    }

    /**
     * Appends one mask to another. This assumes the masks are both padded with '11' on each end
     * to prevent overflow issues.
     */
    public BigInteger appendMask(BigInteger mask1, BigInteger mask2) {
        if (mask1 == null && mask2 == null) {
            return null;
        }
        if (mask1 == null) {
            mask1 = variantStore1.emptyBitmask();
        }
        if (mask2 == null) {
            mask2 = variantStore2.emptyBitmask();
        }
        String binaryMask1 = mask1.toString(2);
        String binaryMask2 = mask2.toString(2);
        String appendedString = binaryMask1.substring(0, binaryMask1.length() - 2) +
                binaryMask2.substring(2);
        return new BigInteger(appendedString, 2);
    }
}
