package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.collect.Range;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Component
public class GenomicProcessor {

    private static Logger log = LoggerFactory.getLogger(GenomicProcessor.class);

    private final PatientVariantJoinHandler patientVariantJoinHandler;

    private final VariantIndexCache variantIndexCache;

    private final Map<String, FileBackedByteIndexedInfoStore> infoStores;

    private final List<String> infoStoreColumns;

    private final String genomicDataDirectory;

    private final VariantService variantService;

    @Autowired
    public GenomicProcessor(PatientVariantJoinHandler patientVariantJoinHandler, VariantIndexCache variantIndexCache, VariantService variantService) {
        this.patientVariantJoinHandler = patientVariantJoinHandler;
        this.variantIndexCache = variantIndexCache;
        this.variantService = variantService;

        genomicDataDirectory = System.getProperty("HPDS_GENOMIC_DATA_DIRECTORY", "/opt/local/hpds/all/");

        infoStores = new HashMap<>();
        File genomicDataDirectory = new File(this.genomicDataDirectory);
        if(genomicDataDirectory.exists() && genomicDataDirectory.isDirectory()) {
            Arrays.stream(genomicDataDirectory.list((file, filename)->{return filename.endsWith("infoStore.javabin");}))
                    .forEach((String filename)->{
                        try (
                                FileInputStream fis = new FileInputStream(this.genomicDataDirectory + filename);
                                GZIPInputStream gis = new GZIPInputStream(fis);
                                ObjectInputStream ois = new ObjectInputStream(gis)
                        ){
                            log.info("loading " + filename);
                            FileBackedByteIndexedInfoStore infoStore = (FileBackedByteIndexedInfoStore) ois.readObject();
                            infoStores.put(filename.replace("_infoStore.javabin", ""), infoStore);
                            ois.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        infoStoreColumns = new ArrayList<>(infoStores.keySet());

        variantIndexCache = new VariantIndexCache(variantService.getVariantIndex(), infoStores);
    }

    protected BigInteger getPatientMaskForVariantInfoFilters(DistributableQuery distributableQuery) {
//		log.debug("filterdIDSets START size: " + filteredIdSets.size());
        /* VARIANT INFO FILTER HANDLING IS MESSY */
        if(!distributableQuery.getVariantInfoFilters().isEmpty()) {
            for(Query.VariantInfoFilter filter : distributableQuery.getVariantInfoFilters()){
                ArrayList<VariantIndex> variantSets = new ArrayList<>();
                addVariantsMatchingFilters(filter, variantSets);
                log.info("Found " + variantSets.size() + " groups of sets for patient identification");
                //log.info("found " + variantSets.stream().mapToInt(Set::size).sum() + " variants for identification");
                if(!variantSets.isEmpty()) {
                    // INTERSECT all the variant sets.
                    VariantIndex intersectionOfInfoFilters = variantSets.get(0);
                    for(VariantIndex variantSet : variantSets) {
                        intersectionOfInfoFilters = intersectionOfInfoFilters.intersection(variantSet);
                    }
                    // Apparently set.size() is really expensive with large sets... I just saw it take 17 seconds for a set with 16.7M entries
                    if(log.isDebugEnabled()) {
                        //IntSummaryStatistics stats = variantSets.stream().collect(Collectors.summarizingInt(set->set.size()));
                        //log.debug("Number of matching variants for all sets : " + stats.getSum());
                        //log.debug("Number of matching variants for intersection of sets : " + intersectionOfInfoFilters.size());
                    }
                    // add filteredIdSet for patients who have matching variants, heterozygous or homozygous for now.
                    return patientVariantJoinHandler.getPatientIdsForIntersectionOfVariantSets(distributableQuery.getPatientIds(), intersectionOfInfoFilters);
                }
            }
        }
        return createMaskForPatientSet(distributableQuery.getPatientIds());
        /* END OF VARIANT INFO FILTER HANDLING */
    }

    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        Set<Integer> ids = new TreeSet<Integer>();
        String bitmaskString = patientMask.toString(2);
        for(int x = 2;x < bitmaskString.length()-2;x++) {
            if('1'==bitmaskString.charAt(x)) {
                String patientId = variantService.getPatientIds()[x-2].trim();
                ids.add(Integer.parseInt(patientId));
            }
        }
        return ids;
    }

    protected void addVariantsMatchingFilters(Query.VariantInfoFilter filter, ArrayList<VariantIndex> variantSets) {
        // Add variant sets for each filter
        if(filter.categoryVariantInfoFilters != null && !filter.categoryVariantInfoFilters.isEmpty()) {
            filter.categoryVariantInfoFilters.entrySet().parallelStream().forEach((Map.Entry<String,String[]> entry) ->{
                addVariantsMatchingCategoryFilter(variantSets, entry);
            });
        }
        if(filter.numericVariantInfoFilters != null && !filter.numericVariantInfoFilters.isEmpty()) {
            filter.numericVariantInfoFilters.forEach((String column, Filter.FloatFilter doubleFilter)->{
                FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);

                doubleFilter.getMax();
                Range<Float> filterRange = Range.closed(doubleFilter.getMin(), doubleFilter.getMax());
                List<String> valuesInRange = infoStore.continuousValueIndex.getValuesInRange(filterRange);
                VariantIndex variants = new SparseVariantIndex(Set.of());
                for(String value : valuesInRange) {
                    variants = variants.union(variantIndexCache.get(column, value));
                }
                variantSets.add(variants);
            });
        }
    }

    private void addVariantsMatchingCategoryFilter(ArrayList<VariantIndex> variantSets, Map.Entry<String, String[]> entry) {
        String column = entry.getKey();
        String[] values = entry.getValue();
        Arrays.sort(values);
        FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);

        List<String> infoKeys = filterInfoCategoryKeys(values, infoStore);
        /*
         * We want to union all the variants for each selected key, so we need an intermediate set
         */
        VariantIndex[] categoryVariantSets = new VariantIndex[] {new SparseVariantIndex(Set.of())};

        if(infoKeys.size()>1) {
            infoKeys.stream().forEach((key)->{
                VariantIndex variantsForColumnAndValue = variantIndexCache.get(column, key);
                categoryVariantSets[0] = categoryVariantSets[0].union(variantsForColumnAndValue);
            });
        } else {
            categoryVariantSets[0] = variantIndexCache.get(column, infoKeys.get(0));
        }
        variantSets.add(categoryVariantSets[0]);
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

    protected BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        return patientVariantJoinHandler.createMaskForPatientSet(patientSubset);
    }

    public FileBackedByteIndexedInfoStore getInfoStore(String column) {
        return infoStores.get(column);
    }

    public VariantIndex addVariantsForInfoFilter(VariantIndex unionOfInfoFilters, Query.VariantInfoFilter filter) {
        ArrayList<VariantIndex> variantSets = new ArrayList<>();
        addVariantsMatchingFilters(filter, variantSets);

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

}
