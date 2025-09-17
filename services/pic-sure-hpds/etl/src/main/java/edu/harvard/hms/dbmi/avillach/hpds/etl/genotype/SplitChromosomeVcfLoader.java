package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class SplitChromosomeVcfLoader extends NewVCFLoader {

    private static Logger logger = LoggerFactory.getLogger(SplitChromosomeVcfLoader.class);
    private String[] allSampleIds;
    private Integer[] patientIds;
    private TreeSet<Integer> allPatientIds;

    private final String baseStorageDir;
    private final String baseMergeDir;

    public SplitChromosomeVcfLoader(File file, String baseStorageDir, String baseMergeDir) {
        super(file, baseStorageDir, baseMergeDir);
        this.baseStorageDir = baseStorageDir;
        this.baseMergeDir = baseMergeDir;
    }

    public SplitChromosomeVcfLoader() {
        super();
        this.baseStorageDir = DEFAULT_STORAGE_DIR;
        this.baseMergeDir = DEFAULT_MERGED_DIR;
    }



    public static void main(String[] args) throws IOException {
        NewVCFLoader vcfLoader;
        if (args != null && args.length >= 3) {
            logger.info("Reading parameters from input");
            vcfLoader = new SplitChromosomeVcfLoader(new File(args[0]), args[1], args[2]);
        } else {
            logger.info(args.length + " arguments provided");
            logger.info("Using default values");
            vcfLoader = new SplitChromosomeVcfLoader();
        }
        vcfLoader.loadAndMerge();

        vcfLoader.shutdownChunkWriteExecutor();
    }


    protected void loadVCFs() throws IOException {
        startTime = System.currentTimeMillis();
        allPatientIds = new TreeSet<>();

        // Pull the INFO columns out of the headers for each walker and add all patient ids
        walkers.stream().forEach(walker -> {
            try {
                logger.info("Reading headers of VCF [" + walker.vcfIndexLine.getVcfPath() + "]");
                walker.readHeaders(infoStoreMap);
                allPatientIds.addAll(Arrays.asList(walker.vcfIndexLine.getPatientIds()));
            } catch (IOException e) {
                logger.error("Error while reading headers of VCF [" + walker.vcfIndexLine.getVcfPath() + "]", e);
                System.exit(-1);
            }
        });

        patientIds = allPatientIds.toArray(new Integer[0]);
        allSampleIds = new String[allPatientIds.size()];

        walkers.parallelStream().forEach(walker -> {
            logger.info("Setting bitmask offsets for VCF [" + walker.vcfIndexLine.getVcfPath() + "]");
            walker.setBitmaskOffsets(patientIds);
            for (int x = 0; x < walker.vcfIndexLine.getSampleIds().length; x++) {
                allSampleIds[Arrays.binarySearch(patientIds, walker.vcfIndexLine.getPatientIds()[x])] =
                    walker.vcfIndexLine.getSampleIds()[x];
            }
        });

        for (VCFWalker walker : walkers) {
            chunkWriteEx = Executors.newFixedThreadPool(1);
            storageDirStr = baseStorageDir + "/" + walker.currentContig;
            storageDir = new File(storageDirStr);
            storageDir.mkdirs();
            mergedDirStr = baseMergeDir + "/" + walker.currentContig;
            new File(mergedDirStr).mkdirs();
            variantIndexBuilder = new VariantIndexBuilder();
            variantMaskStorage = new TreeMap<>();
            loadSingleContig(walker);
        }
    }

    private void loadSingleContig(VCFWalker walker) throws IOException {
        VariantStore store = new VariantStore();
        store.setPatientIds(allPatientIds.stream().map((id) -> {
            return id.toString();
        }).collect(Collectors.toList()).toArray(new String[0]));

        String lastContigProcessed = null;
        int lastChunkProcessed = 0;
        int currentChunk = 0;
        String[] currentContig = new String[1];
        int[] currentPosition = {-1};
        String[] currentRef = new String[1];
        String[] currentAlt = new String[1];
        String[] currentVariantSpec = new String[1];

        zygosityMaskStrings = new HashMap<String/* variantSpec */, char[][]/* string bitmasks */>();

        List<Integer> positionsProcessedInChunk = new ArrayList<>();

        while (walker.hasNext) {
            String currentSpecNotation = walker.currentSpecNotation();
            currentContig[0] = walker.currentContig;
            currentPosition[0] = walker.currentPosition;
            currentRef[0] = walker.currentRef;
            currentAlt[0] = walker.currentAlt;
            currentVariantSpec[0] = walker.currentSpecNotation();
            currentChunk = walker.currentPosition / CHUNK_SIZE;
            positionsProcessedInChunk.add(currentPosition[0]);

            if (lastContigProcessed == null) {
                lastContigProcessed = walker.currentContig;
            }

            flipChunk(lastContigProcessed, lastChunkProcessed, currentChunk, currentContig[0], false, walker.currentLine);
            lastContigProcessed = walker.currentContig;
            lastChunkProcessed = currentChunk;

            char[][][] maskStringsForVariantSpec = {zygosityMaskStrings.get(currentSpecNotation)};
            if (maskStringsForVariantSpec[0] == null) {
                maskStringsForVariantSpec[0] = new char[7][allPatientIds.size()];
                for (int x = 0; x < maskStringsForVariantSpec[0].length; x++) {
                    maskStringsForVariantSpec[0][x] = new char[allPatientIds.size()];
                    for (int y = 0; y < allPatientIds.size(); y++) {
                        maskStringsForVariantSpec[0][x][y] = '0';
                    }
                }
            }

            while (Objects.equals(walker.currentSpecNotation(), currentVariantSpec[0]) && walker.hasNext) {
                walker.updateRecords(maskStringsForVariantSpec[0], infoStoreMap);
                try {
                    walker.nextLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            zygosityMaskStrings.put(currentSpecNotation, maskStringsForVariantSpec[0]);
        }

        flipChunk(lastContigProcessed, lastChunkProcessed, currentChunk, currentContig[0], true, null);

        shutdownChunkWriteExecutor();

        saveInfoStores();

        splitInfoStoresByColumn();

        convertInfoStoresToByteIndexed();

        if (logger.isDebugEnabled()) {
            // Log out the first and last 50 variants
            int[] count = {0};
            for (String contig : store.getVariantMaskStorage().keySet()) {
                ArrayList<Integer> chunkIds = new ArrayList<>();
                FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> chromosomeStorage =
                    store.getVariantMaskStorage().get(contig);
                if (chromosomeStorage != null) {
                    // print out the top and bottom 50 variants in the store (that have masks)
                    chunkIds.addAll(chromosomeStorage.keys());
                    for (Integer chunkId : chunkIds) {
                        for (String variantSpec : chromosomeStorage.get(chunkId).keySet()) {
                            count[0]++;
                            VariableVariantMasks variantMasks = chromosomeStorage.get(chunkId).get(variantSpec);
                            if (variantMasks != null) {
                                VariantMask heterozygousMask = variantMasks.heterozygousMask;
                                String heteroIdList = sampleIdsForMask(allSampleIds, heterozygousMask);
                                VariantMask homozygousMask = variantMasks.homozygousMask;
                                String homoIdList = sampleIdsForMask(allSampleIds, homozygousMask);

                                if (!heteroIdList.isEmpty() && heteroIdList.length() < 1000)
                                    logger.debug(variantSpec + " : heterozygous : " + heteroIdList);
                                if (!homoIdList.isEmpty() && homoIdList.length() < 1000)
                                    logger.debug(variantSpec + " : homozygous : " + homoIdList);
                            }
                        }
                        if (count[0] > 50) break;
                    }

                    count[0] = 0;
                    for (int x = chunkIds.size() - 1; x > 0; x--) {
                        int chunkId = chunkIds.get(x);
                        chromosomeStorage.get(chunkId).keySet().forEach((variantSpec) -> {
                            count[0]++;
                            VariableVariantMasks variantMasks = chromosomeStorage.get(chunkId).get(variantSpec);
                            if (variantMasks != null) {
                                VariantMask heterozygousMask = variantMasks.heterozygousMask;
                                String heteroIdList = sampleIdsForMask(allSampleIds, heterozygousMask);
                                VariantMask homozygousMask = variantMasks.homozygousMask;
                                String homoIdList = sampleIdsForMask(allSampleIds, homozygousMask);

                                if (!heteroIdList.isEmpty() && heteroIdList.length() < 1000)
                                    logger.debug(variantSpec + " : heterozygous : " + heteroIdList);
                                if (!homoIdList.isEmpty() && homoIdList.length() < 1000)
                                    logger.debug(variantSpec + " : homozygous : " + homoIdList);
                            }
                        });
                        if (count[0] > 50) break;
                    }
                }
            }
        }

        store.setVariantSpecIndex(variantIndexBuilder.getVariantSpecIndex().toArray(new String[0]));
        saveVariantStore(store, variantMaskStorage);
    }
}
