package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.base.Joiner;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class GenomicProcessorNodeImpl implements GenomicProcessor {

    private static Logger log = LoggerFactory.getLogger(GenomicProcessorNodeImpl.class);

    private final PatientVariantJoinHandler patientVariantJoinHandler;

    private final VariantIndexCache variantIndexCache;

    private final Map<String, FileBackedByteIndexedInfoStore> infoStores;

    private final List<String> infoStoreColumns;

    private final String genomicDataDirectory;

    private final VariantService variantService;


    private final String HOMOZYGOUS_VARIANT = "1/1";
    private final String HETEROZYGOUS_VARIANT = "0/1";
    private final String HOMOZYGOUS_REFERENCE = "0/0";

    public GenomicProcessorNodeImpl(String genomicDataDirectory) {
        this.genomicDataDirectory = genomicDataDirectory;
        this.variantService = new VariantService(genomicDataDirectory);
        this.patientVariantJoinHandler = new PatientVariantJoinHandler(variantService);

        infoStores = new HashMap<>();
        File genomicDataDirectoryFile = new File(this.genomicDataDirectory);
        if(genomicDataDirectoryFile.exists() && genomicDataDirectoryFile.isDirectory()) {
            Arrays.stream(genomicDataDirectoryFile.list((file, filename)->{return filename.endsWith("infoStore.javabin");}))
                    .forEach((String filename)->{
                        try (
                                FileInputStream fis = new FileInputStream(this.genomicDataDirectory + filename);
                                GZIPInputStream gis = new GZIPInputStream(fis);
                                ObjectInputStream ois = new ObjectInputStream(gis)
                        ){
                            log.info("loading " + filename);
                            FileBackedByteIndexedInfoStore infoStore = (FileBackedByteIndexedInfoStore) ois.readObject();
                            infoStore.updateStorageDirectory(genomicDataDirectoryFile);
                            infoStores.put(filename.replace("_infoStore.javabin", ""), infoStore);
                            ois.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } else {
            throw new IllegalArgumentException("Not a valid genomicDataDirectory: " + this.genomicDataDirectory);
        }
        infoStoreColumns = new ArrayList<>(infoStores.keySet());

        variantIndexCache = new VariantIndexCache(variantService.getVariantIndex(), infoStores);
    }

    @Override
    public Mono<BigInteger> getPatientMask(DistributableQuery distributableQuery) {
        return Mono.fromCallable(() -> runGetPatientMask(distributableQuery)).subscribeOn(Schedulers.boundedElastic());
    }
    public BigInteger runGetPatientMask(DistributableQuery distributableQuery) {
//		log.debug("filterdIDSets START size: " + filteredIdSets.size());
        /* VARIANT INFO FILTER HANDLING IS MESSY */
        if(distributableQuery.hasFilters()) {
            VariantIndex intersectionOfInfoFilters = null;
            for(Query.VariantInfoFilter filter : distributableQuery.getVariantInfoFilters()){
                List<VariantIndex> variantSets = getVariantsMatchingFilters(filter);
                log.info("Found " + variantSets.size() + " groups of sets for patient identification");
                if(!variantSets.isEmpty()) {
                    // INTERSECT all the variant sets.
                    intersectionOfInfoFilters = variantSets.get(0);
                    for(VariantIndex variantSet : variantSets) {
                        intersectionOfInfoFilters = intersectionOfInfoFilters.intersection(variantSet);
                    }
                } else {
                    // todo: create an empty variant index implementation
                    intersectionOfInfoFilters = new SparseVariantIndex(Set.of());
                }
            }
            // todo: handle empty getVariantInfoFilters()

            // add filteredIdSet for patients who have matching variants, heterozygous or homozygous for now.
            BigInteger patientMask = null;
            if (intersectionOfInfoFilters != null ){
                patientMask = patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(distributableQuery.getPatientIds(), intersectionOfInfoFilters);
            } else {
                patientMask = createMaskForPatientSet(distributableQuery.getPatientIds());
            }


            VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder = new VariantBucketHolder<>();
            if (!distributableQuery.getRequiredFields().isEmpty() ) {
                for (String variantSpec : distributableQuery.getRequiredFields()) {
                    BigInteger patientsForVariantSpec = getIdSetForVariantSpecCategoryFilter(new String[]{"0/1", "1/1"}, variantSpec, variantMasksVariantBucketHolder);
                    if (patientMask == null) {
                        patientMask = patientsForVariantSpec;
                    } else {
                        patientMask = patientMask.and(patientsForVariantSpec);
                    }
                }
            }
            if (!distributableQuery.getCategoryFilters().isEmpty()) {
                for (Map.Entry<String, String[]> categoryFilterEntry : distributableQuery.getCategoryFilters().entrySet()) {
                    BigInteger patientsForVariantSpec = getIdSetForVariantSpecCategoryFilter(categoryFilterEntry.getValue(), categoryFilterEntry.getKey(), null);
                    if (patientMask == null) {
                        patientMask = patientsForVariantSpec;
                    } else {
                        patientMask = patientMask.and(patientsForVariantSpec);
                    }
                }
            }

            return patientMask;
        }
        return createMaskForPatientSet(distributableQuery.getPatientIds());
        /* END OF VARIANT INFO FILTER HANDLING */
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        Set<Integer> ids = new HashSet<>();
        String bitmaskString = patientMask.toString(2);
        for(int x = 2;x < bitmaskString.length()-2;x++) {
            if('1'==bitmaskString.charAt(x)) {
                String patientId = variantService.getPatientIds()[x-2].trim();
                ids.add(Integer.parseInt(patientId));
            }
        }
        return ids;
    }

    private List<VariantIndex> getVariantsMatchingFilters(Query.VariantInfoFilter filter) {
        List<VariantIndex> variantIndices = new ArrayList<>();
        // Add variant sets for each filter
        if(filter.categoryVariantInfoFilters != null && !filter.categoryVariantInfoFilters.isEmpty()) {
            filter.categoryVariantInfoFilters.entrySet().stream().forEach((Map.Entry<String,String[]> entry) ->{
                variantIndices.addAll(getVariantIndicesForCategoryFilter(entry));
            });
        }
        if(filter.numericVariantInfoFilters != null && !filter.numericVariantInfoFilters.isEmpty()) {
            filter.numericVariantInfoFilters.forEach((String column, Filter.FloatFilter doubleFilter)->{
                FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);

                doubleFilter.getMax();
                Range<Float> filterRange = Range.closed(doubleFilter.getMin(), doubleFilter.getMax());
                List<String> valuesInRange = infoStore.continuousValueIndex.getValuesInRange(filterRange);
                for(String value : valuesInRange) {
                    variantIndices.add(variantIndexCache.get(column, value));
                }
            });
        }
        return variantIndices;
    }

    private List<VariantIndex> getVariantIndicesForCategoryFilter(Map.Entry<String, String[]> entry) {
        String column = entry.getKey();
        String[] values = entry.getValue();
        Arrays.sort(values);
        FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);

        List<String> infoKeys = filterInfoCategoryKeys(values, infoStore);

        if(infoKeys.size()>1) {
            return infoKeys.stream()
                    .map(key -> variantIndexCache.get(column, key))
                    .collect(Collectors.toList());
        } else if(infoKeys.size() == 1) {
            return List.of(variantIndexCache.get(column, infoKeys.get(0)));
        } else { // infoKeys.size() == 0
            log.info("No indexes found for column [" + column + "] for values [" + Joiner.on(",").join(values) + "]");
            // todo: test this case. should this be empty list or a list with an empty VariantIndex?
            return List.of();
        }
    }

    private List<String> filterInfoCategoryKeys(String[] values, FileBackedByteIndexedInfoStore infoStore) {
        List<String> infoKeys = infoStore.getAllValues().keys().stream().filter((String key)->{
            // iterate over the values for the specific category and find which ones match the search
            int insertionIndex = Arrays.binarySearch(values, key);
            return insertionIndex > -1 && insertionIndex < values.length;
        }).collect(Collectors.toList());
        log.info("found " + infoKeys.size() + " keys");
        return infoKeys;
    }

    @Override
    public BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        return patientVariantJoinHandler.createMaskForPatientSet(patientSubset);
    }

    private FileBackedByteIndexedInfoStore getInfoStore(String column) {
        return infoStores.get(column);
    }

    private VariantIndex addVariantsForInfoFilter(VariantIndex unionOfInfoFilters, Query.VariantInfoFilter filter) {
        List<VariantIndex> variantSets = getVariantsMatchingFilters(filter);

        if(!variantSets.isEmpty()) {
            VariantIndex intersectionOfInfoFilters = variantSets.get(0);
            for(VariantIndex variantSet : variantSets) {
                //						log.info("Variant Set : " + Arrays.deepToString(variantSet.toArray()));
                intersectionOfInfoFilters = intersectionOfInfoFilters.intersection(variantSet);
            }
            unionOfInfoFilters = unionOfInfoFilters.union(intersectionOfInfoFilters);
        } else {
            log.warn("No info filters included in query.");
        }
        return unionOfInfoFilters;
    }

    @Override
    public Mono<Collection<String>> getVariantList(DistributableQuery query) {
        boolean queryContainsVariantInfoFilters = query.getVariantInfoFilters().stream().anyMatch(variantInfoFilter ->
                !variantInfoFilter.categoryVariantInfoFilters.isEmpty() || !variantInfoFilter.numericVariantInfoFilters.isEmpty()
        );
        if(queryContainsVariantInfoFilters) {
            VariantIndex unionOfInfoFilters = new SparseVariantIndex(Set.of());

            // todo: are these not the same thing?
            if(query.getVariantInfoFilters().size()>1) {
                for(Query.VariantInfoFilter filter : query.getVariantInfoFilters()){
                    unionOfInfoFilters = addVariantsForInfoFilter(unionOfInfoFilters, filter);
                    //log.info("filter " + filter + "  sets: " + Arrays.deepToString(unionOfInfoFilters.toArray()));
                }
            } else {
                unionOfInfoFilters = addVariantsForInfoFilter(unionOfInfoFilters, query.getVariantInfoFilters().get(0));
            }

            HashSet<Integer> allPatients = new HashSet<>(
                    Arrays.stream(variantService.getPatientIds())
                            .map((id) -> {
                                return Integer.parseInt(id.trim());
                            })
                            .collect(Collectors.toList()));
            Set<Integer> patientSubset = Sets.intersection(query.getPatientIds(), allPatients);
//			log.debug("Patient subset " + Arrays.deepToString(patientSubset.toArray()));

            // If we have all patients then no variants would be filtered, so no need to do further processing
            if(patientSubset.size()==variantService.getPatientIds().length) {
                log.info("query selects all patient IDs, returning....");
                return Mono.just(unionOfInfoFilters.mapToVariantSpec(variantService.getVariantIndex()));
            }

            BigInteger patientMasks = createMaskForPatientSet(patientSubset);

            Set<String> unionOfInfoFiltersVariantSpecs = unionOfInfoFilters.mapToVariantSpec(variantService.getVariantIndex());
            Collection<String> variantsInScope = variantService.filterVariantSetForPatientSet(unionOfInfoFiltersVariantSpecs, new ArrayList<>(patientSubset));

            //NC - this is the original variant filtering, which checks the patient mask from each variant against the patient mask from the query
            if(variantsInScope.size()<100000) {
                ConcurrentSkipListSet<String> variantsWithPatients = new ConcurrentSkipListSet<String>();
                variantsInScope.parallelStream().forEach(variantKey -> {
                    variantService.getMasks(variantKey, new VariantBucketHolder<>()).ifPresent(masks -> {
                        if ( masks.heterozygousMask != null && masks.heterozygousMask.and(patientMasks).bitCount()>4) {
                            variantsWithPatients.add(variantKey);
                        } else if ( masks.homozygousMask != null && masks.homozygousMask.and(patientMasks).bitCount()>4) {
                            variantsWithPatients.add(variantKey);
                        } else if ( masks.heterozygousNoCallMask != null && masks.heterozygousNoCallMask.and(patientMasks).bitCount()>4) {
                            //so heterozygous no calls we want, homozygous no calls we don't
                            variantsWithPatients.add(variantKey);
                        }
                    });
                });
                return Mono.just(variantsWithPatients);
            }else {
                return Mono.just(unionOfInfoFiltersVariantSpecs);
            }
        }
        return Mono.just(new ArrayList<>());
    }

    private BigInteger getIdSetForVariantSpecCategoryFilter(String[] zygosities, String key, VariantBucketHolder<VariantMasks> bucketCache) {
        List<BigInteger> variantBitmasks = getBitmasksForVariantSpecCategoryFilter(zygosities, key, bucketCache);
        Set<Integer> patientIds = new HashSet<>();
        if(!variantBitmasks.isEmpty()) {
            BigInteger bitmask = variantBitmasks.get(0);
            if(variantBitmasks.size()>1) {
                for(int x = 1;x<variantBitmasks.size();x++) {
                    bitmask = bitmask.or(variantBitmasks.get(x));
                }
            }
            return bitmask;
        }
        return createMaskForPatientSet(new HashSet<>());
    }

    private ArrayList<BigInteger> getBitmasksForVariantSpecCategoryFilter(String[] zygosities, String variantName, VariantBucketHolder<VariantMasks> bucketCache) {
        ArrayList<BigInteger> variantBitmasks = new ArrayList<>();
        variantName = variantName.replaceAll(",\\d/\\d$", "");
        log.debug("looking up mask for : " + variantName);
        Optional<VariantMasks> optionalMasks = variantService.getMasks(variantName, bucketCache);
        Arrays.stream(zygosities).forEach((zygosity) -> {
            optionalMasks.ifPresent(masks -> {
                if(zygosity.equals(HOMOZYGOUS_REFERENCE)) {
                    BigInteger homozygousReferenceBitmask = calculateIndiscriminateBitmask(masks);
                    for(int x = 2;x<homozygousReferenceBitmask.bitLength()-2;x++) {
                        homozygousReferenceBitmask = homozygousReferenceBitmask.flipBit(x);
                    }
                    variantBitmasks.add(homozygousReferenceBitmask);
                } else if(masks.heterozygousMask != null && zygosity.equals(HETEROZYGOUS_VARIANT)) {
                    variantBitmasks.add(masks.heterozygousMask);
                }else if(masks.homozygousMask != null && zygosity.equals(HOMOZYGOUS_VARIANT)) {
                    variantBitmasks.add(masks.homozygousMask);
                }else if(zygosity.equals("")) {
                    variantBitmasks.add(calculateIndiscriminateBitmask(masks));
                }
            });
            if (optionalMasks.isEmpty()) {
                variantBitmasks.add(variantService.emptyBitmask());
            }

        });
        return variantBitmasks;
    }

    /**
     * Calculate a bitmask which is a bitwise OR of any populated masks in the VariantMasks passed in
     * @param masks
     * @return
     */
    private BigInteger calculateIndiscriminateBitmask(VariantMasks masks) {
        BigInteger indiscriminateVariantBitmask = null;
        if(masks.heterozygousMask == null && masks.homozygousMask != null) {
            indiscriminateVariantBitmask = masks.homozygousMask;
        }else if(masks.homozygousMask == null && masks.heterozygousMask != null) {
            indiscriminateVariantBitmask = masks.heterozygousMask;
        }else if(masks.homozygousMask != null && masks.heterozygousMask != null) {
            indiscriminateVariantBitmask = masks.heterozygousMask.or(masks.homozygousMask);
        }else {
            indiscriminateVariantBitmask = variantService.emptyBitmask();
        }
        return indiscriminateVariantBitmask;
    }

    @Override
    public List<String> getPatientIds() {
        return List.of(variantService.getPatientIds());
    }

    @Override
    public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
        return variantService.getMasks(path, variantMasksVariantBucketHolder);
    }

    @Override
    public List<String> getInfoStoreColumns() {
        return infoStoreColumns;
    }

    @Override
    public List<String> getInfoStoreValues(String conceptPath) {
        return infoStores.get(conceptPath).getAllValues().keys()
                .stream()
                .sorted(String::compareToIgnoreCase)
                .collect(Collectors.toList());
    }

    @Override
    public List<InfoColumnMeta> getInfoColumnMeta() {
        return getInfoStoreColumns().stream().map(infoStores::get)
                .map(fileBackedByteIndexedInfoStore -> InfoColumnMeta.builder()
                        .key(fileBackedByteIndexedInfoStore.column_key)
                        .description(fileBackedByteIndexedInfoStore.description)
                        .continuous(fileBackedByteIndexedInfoStore.isContinuous)
                        .min(fileBackedByteIndexedInfoStore.min)
                        .max(fileBackedByteIndexedInfoStore.max)
                        .build()
                )
                .collect(Collectors.toList());
    }
}
