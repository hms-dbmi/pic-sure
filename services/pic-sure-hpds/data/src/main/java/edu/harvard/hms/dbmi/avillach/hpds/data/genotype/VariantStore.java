package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.google.common.collect.RangeSet;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJsonIndexStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class VariantStore implements Serializable {
    private static final long serialVersionUID = -6970128712587609414L;
    public static final String VARIANT_STORE_JAVABIN_FILENAME = "variantStore.javabin";
    public static final String VARIANT_SPEC_INDEX_JAVABIN_FILENAME = "variantSpecIndex.javabin";
    private static Logger log = LoggerFactory.getLogger(VariantStore.class);
    public static final int BUCKET_SIZE = 1000;

    private BigInteger emptyBitmask;
    private String[] patientIds;

    private transient String[] variantSpecIndex;

    private Map<String, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> variantMaskStorage =
        new TreeMap<>();

    public Map<String, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> getVariantMaskStorage() {
        return variantMaskStorage;
    }

    public void setVariantMaskStorage(
        Map<String, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> variantMaskStorage
    ) {
        this.variantMaskStorage = variantMaskStorage;
    }

    public String[] getVariantSpecIndex() {
        return variantSpecIndex;
    }

    public void setVariantSpecIndex(String[] variantSpecIndex) {
        this.variantSpecIndex = variantSpecIndex;
    }

    public static VariantStore readInstance(String genomicDataDirectory) throws IOException, ClassNotFoundException {
        try (
            GZIPInputStream gzipInputStream =
                new GZIPInputStream(new FileInputStream(genomicDataDirectory + VARIANT_STORE_JAVABIN_FILENAME)); ObjectInputStream ois =
                    new ObjectInputStream(gzipInputStream)
        ) {
            VariantStore variantStore = (VariantStore) ois.readObject();
            ois.close();
            variantStore.getVariantMaskStorage().values().forEach(store -> {
                store.updateStorageDirectory(new File(genomicDataDirectory));
            });
            variantStore.open();
            variantStore.setVariantSpecIndex(loadVariantIndexFromFile(genomicDataDirectory));
            return variantStore;
        }
    }

    public void writeInstance(String genomicDirectory) {
        try (
            FileOutputStream fos = new FileOutputStream(new File(genomicDirectory, VARIANT_STORE_JAVABIN_FILENAME)); GZIPOutputStream gzos =
                new GZIPOutputStream(fos); ObjectOutputStream oos = new ObjectOutputStream(gzos);
        ) {
            oos.writeObject(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (
            FileOutputStream fos =
                new FileOutputStream(new File(genomicDirectory, VARIANT_SPEC_INDEX_JAVABIN_FILENAME)); GZIPOutputStream gzos =
                    new GZIPOutputStream(fos); ObjectOutputStream oos = new ObjectOutputStream(gzos);
        ) {
            oos.writeObject(Arrays.asList(variantSpecIndex));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String[] getPatientIds() {
        return patientIds;
    }

    public Optional<VariableVariantMasks> getMasks(String variant, VariantBucketHolder<VariableVariantMasks> bucketCache)
        throws IOException {
        String[] segments = variant.split(",");
        if (segments.length < 2) {
            log.error("Less than 2 segments found in this variant : " + variant);
        }

        int chrOffset = Integer.parseInt(segments[1]) / BUCKET_SIZE;
        String contig = segments[0];

        if (bucketCache.lastValue != null && contig.contentEquals(bucketCache.lastContig) && chrOffset == bucketCache.lastChunkOffset) {
            // TODO : This is a temporary efficiency hack, NOT THREADSAFE!!!
        } else {
            // todo: don't bother doing a lookup if this node does not have the chromosome specified
            FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> indexedStorage =
                variantMaskStorage.get(contig);
            if (indexedStorage == null) {
                return Optional.empty();
            }
            bucketCache.lastValue = indexedStorage.get(chrOffset);
            bucketCache.lastContig = contig;
            bucketCache.lastChunkOffset = chrOffset;
        }
        return bucketCache.lastValue == null ? Optional.empty() : Optional.ofNullable(bucketCache.lastValue.get(variant));
    }

    public List<VariableVariantMasks> getMasksForDbSnpSpec(String variant) {
        String[] segments = variant.split(",");
        if (segments.length < 2) {
            log.error("Less than 2 segments found in this variant : " + variant);
        }

        int chrOffset = Integer.parseInt(segments[1]) / BUCKET_SIZE;
        String contig = segments[0];

        // todo: don't bother doing a lookup if this node does not have the chromosome specified
        FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> indexedStorage =
            variantMaskStorage.get(contig);
        if (indexedStorage == null) {
            return List.of();
        } else {
            ConcurrentHashMap<String, VariableVariantMasks> specToMaskMap = indexedStorage.getOrELse(chrOffset, new ConcurrentHashMap<>());
            return specToMaskMap.entrySet().stream().filter(entry -> entry.getKey().startsWith(variant)).map(Map.Entry::getValue)
                .collect(Collectors.toList());
        }
    }

    public void open() {
        variantMaskStorage.values().stream().forEach((fbbis -> {
            if (fbbis != null) {
                fbbis.open();
            }
        }));
    }

    public VariantStore() {

    }

    public void setPatientIds(String[] patientIds) {
        this.patientIds = patientIds;
    }

    public BigInteger emptyBitmask() {
        if (emptyBitmask == null || emptyBitmask.testBit(emptyBitmask.bitLength() / 2)) {
            String emptyVariantMask = "";
            for (String patientId : patientIds) {
                emptyVariantMask = emptyVariantMask + "0";
            }
            emptyBitmask = new BigInteger("11" + emptyVariantMask + "11", 2);
        }
        return emptyBitmask;
    }

    @SuppressWarnings("unchecked")
    public static String[] loadVariantIndexFromFile(String genomicDataDirectory) {
        try (
            ObjectInputStream objectInputStream = new ObjectInputStream(
                new GZIPInputStream(new FileInputStream(genomicDataDirectory + "/" + VARIANT_SPEC_INDEX_JAVABIN_FILENAME))
            );
        ) {

            List<String> variants = (List<String>) objectInputStream.readObject();
            return variants.toArray(new String[0]);

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
