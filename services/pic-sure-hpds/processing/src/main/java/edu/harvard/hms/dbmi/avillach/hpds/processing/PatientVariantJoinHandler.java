package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantSpec;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class PatientVariantJoinHandler {

    private static Logger log = LoggerFactory.getLogger(PatientVariantJoinHandler.class);

    private final VariantService variantService;

    @Autowired
    public PatientVariantJoinHandler(VariantService variantService) {
        this.variantService = variantService;
    }

    public List<Set<Integer>> getPatientIdsForIntersectionOfVariantSets(List<Set<Integer>> filteredIdSets,
                                                                        VariantIndex intersectionOfInfoFilters) {

        List<Set<Integer>> returnList = new ArrayList<>(filteredIdSets);
        if(!intersectionOfInfoFilters.isEmpty()) {
            Set<Integer> patientsInScope;
            Set<Integer> patientIds = Arrays.asList(
                    variantService.getPatientIds()).stream().map((String id)->{
                return Integer.parseInt(id);}).collect(Collectors.toSet());
            if(!filteredIdSets.isEmpty()) {
                patientsInScope = Sets.intersection(patientIds, filteredIdSets.get(0));
            } else {
                patientsInScope = patientIds;
            }

            BigInteger[] matchingPatients = new BigInteger[] {variantService.emptyBitmask()};

            Set<String> variantsInScope = intersectionOfInfoFilters.mapToVariantSpec(variantService.getVariantIndex());

            Collection<List<String>> values = variantsInScope.stream()
                    .collect(Collectors.groupingByConcurrent((variantSpec) -> {
                        return new VariantSpec(variantSpec).metadata.offset / 1000;
                    })).values();
            ArrayList<List<String>> variantBucketsInScope = new ArrayList<List<String>>(values);

            log.info("found " + variantBucketsInScope.size() + " buckets");

            //don't error on small result sets (make sure we have at least one element in each partition)
            int partitionSize = variantBucketsInScope.size() / Runtime.getRuntime().availableProcessors();
            List<List<List<String>>> variantBucketPartitions = Lists.partition(variantBucketsInScope, partitionSize > 0 ? partitionSize : 1);

            log.info("and partitioned those into " + variantBucketPartitions.size() + " groups");

            int patientsInScopeSize = patientsInScope.size();
            BigInteger patientsInScopeMask = createMaskForPatientSet(patientsInScope);
            for(int x = 0;
                x < variantBucketPartitions.size() && matchingPatients[0].bitCount() < patientsInScopeSize + 4;
                x++) {
                List<List<String>> variantBuckets = variantBucketPartitions.get(x);
                variantBuckets.parallelStream().forEach((variantBucket)->{
                    VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<VariantMasks>();
                    variantBucket.stream().forEach((variantSpec)->{
                        VariantMasks masks;
                        masks = variantService.getMasks(variantSpec, bucketCache);
                        if(masks != null) {
                            BigInteger heteroMask = masks.heterozygousMask == null ? variantService.emptyBitmask() : masks.heterozygousMask;
                            BigInteger homoMask = masks.homozygousMask == null ? variantService.emptyBitmask() : masks.homozygousMask;
                            BigInteger orMasks = heteroMask.or(homoMask);
                            BigInteger andMasks = orMasks.and(patientsInScopeMask);
                            synchronized(matchingPatients) {
                                matchingPatients[0] = matchingPatients[0].or(andMasks);
                            }
                        }
                    });
                });
            }
            Set<Integer> ids = new TreeSet<Integer>();
            String bitmaskString = matchingPatients[0].toString(2);
            for(int x = 2;x < bitmaskString.length()-2;x++) {
                if('1'==bitmaskString.charAt(x)) {
                    String patientId = variantService.getPatientIds()[x-2].trim();
                    ids.add(Integer.parseInt(patientId));
                }
            }
            returnList.add(ids);
            return returnList;

        }else {
            log.error("No matches found for info filters.");
            returnList.add(new TreeSet<>());
            return returnList;
        }
    }

    public BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        StringBuilder builder = new StringBuilder("11"); //variant bitmasks are bookended with '11'
        for(String patientId : variantService.getPatientIds()) {
            Integer idInt = Integer.parseInt(patientId);
            if(patientSubset.contains(idInt)){
                builder.append("1");
            } else {
                builder.append("0");
            }
        }
        builder.append("11"); // masks are bookended with '11' set this so we don't count those

//		log.debug("PATIENT MASK: " + builder.toString());

        BigInteger patientMasks = new BigInteger(builder.toString(), 2);
        return patientMasks;
    }
}
