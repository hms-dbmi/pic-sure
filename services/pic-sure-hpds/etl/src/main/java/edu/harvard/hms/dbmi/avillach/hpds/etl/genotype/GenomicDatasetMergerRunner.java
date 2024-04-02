package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import com.google.common.base.Preconditions;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJsonIndexStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

public class GenomicDatasetMergerRunner {

    private static Logger log = LoggerFactory.getLogger(GenomicDatasetMerger.class);

    public static final String INFO_STORE_JAVABIN_SUFFIX = "infoStore.javabin";
    public static final String VARIANT_SPEC_INDEX_FILENAME = "variantSpecIndex.javabin";

    private static String genomicDirectory1;
    private static String genomicDirectory2;

    /**
     * args[0]: directory containing genomic dataset 1
     * args[1]: directory containing genomic dataset 2
     * args[2]: output directory
     */
    public static void main(String[] args) throws IOException, ClassNotFoundException, InterruptedException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Three arguments must be provided: source directory 1, source directory 2, output directory");
        }
        genomicDirectory1 = args[0];
        genomicDirectory2 = args[1];
        String outputDirectory = args[2];

        Map<String, FileBackedByteIndexedInfoStore> infoStores1 = loadInfoStores(genomicDirectory1);
        Map<String, FileBackedByteIndexedInfoStore> infoStores2 = loadInfoStores(genomicDirectory2);

        GenomicDatasetMerger genomicDatasetMerger = new GenomicDatasetMerger(VariantStore.readInstance(genomicDirectory1),VariantStore.readInstance(genomicDirectory2), infoStores1, infoStores2, outputDirectory);
        genomicDatasetMerger.mergePatients();

        Map<String, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> mergedChromosomeMasks = genomicDatasetMerger.mergeChromosomeMasks();
        VariantStore mergedVariantStore = genomicDatasetMerger.mergeVariantStore(mergedChromosomeMasks);
        Map<String, FileBackedByteIndexedInfoStore> variantIndexes = genomicDatasetMerger.mergeVariantIndexes();

        mergedVariantStore.writeInstance(outputDirectory);
        variantIndexes.values().forEach(variantIndex -> {
            variantIndex.write(new File(outputDirectory + variantIndex.column_key + "_" + INFO_STORE_JAVABIN_SUFFIX));
        });
    }

    private static Map<String, FileBackedByteIndexedInfoStore> loadInfoStores(String directory) {
        Map<String, FileBackedByteIndexedInfoStore> infoStores = new HashMap<>();
        File genomicDataDirectory = new File(directory);
        if(genomicDataDirectory.exists() && genomicDataDirectory.isDirectory()) {
            Arrays.stream(genomicDataDirectory.list((file, filename)->{return filename.endsWith(INFO_STORE_JAVABIN_SUFFIX);}))
                    .forEach((String filename)->{
                        try (
                                FileInputStream fis = new FileInputStream(directory + filename);
                                GZIPInputStream gis = new GZIPInputStream(fis);
                                ObjectInputStream ois = new ObjectInputStream(gis)
                        ){
                            log.info("loading " + filename);
                            FileBackedByteIndexedInfoStore infoStore = (FileBackedByteIndexedInfoStore) ois.readObject();
                            infoStore.updateStorageDirectory(genomicDataDirectory);
                            infoStores.put(filename.replace("_" + INFO_STORE_JAVABIN_SUFFIX, ""), infoStore);
                        } catch (IOException | ClassNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return infoStores;
    }
}
