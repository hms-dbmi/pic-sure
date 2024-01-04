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

public class PatientVariantJoinHandler {

    private static Logger log = LoggerFactory.getLogger(PatientVariantJoinHandler.class);

    private final VariantService variantService;

    public PatientVariantJoinHandler(VariantService variantService) {
        this.variantService = variantService;
    }

    public BigInteger getPatientIdsForIntersectionOfVariantSets(Set<Integer> patientSubset,
                                                                        VariantIndex intersectionOfInfoFilters) {

        if(!intersectionOfInfoFilters.isEmpty()) {
            Set<Integer> patientsInScope;
            // todo: don't do this every time
            Set<Integer> patientIds = Arrays.asList(
                    variantService.getPatientIds()).stream().map((String id)->{
                return Integer.parseInt(id);}).collect(Collectors.toSet());
            if(!patientSubset.isEmpty()) {
                // for now, empty means there were no phenotypic filters and all patients are eligible. we should
                // change this to be nullable or have a separate method, this is very counter intuitive
                patientsInScope = Sets.intersection(patientIds, patientSubset);
            } else {
                patientsInScope = patientIds;
            }

            BigInteger[] matchingPatients = new BigInteger[] {variantService.emptyBitmask()};

            Set<String> variantsInScope = intersectionOfInfoFilters.mapToVariantSpec(variantService.getVariantIndex());

            // todo: use BucketIndexBySample.filterVariantSetForPatientSet here?
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
/*            for(int x = 0;
                x < variantBucketPartitions.size() && matchingPatients[0].bitCount() < patientsInScopeSize + 4;
                x++) {
                List<List<String>> variantBuckets = variantBucketPartitions.get(x);
                variantBuckets.parallelStream().forEach(variantBucket -> {
                    VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<>();
                    variantBucket.forEach(variantSpec -> {
                        variantService.getMasks(variantSpec, bucketCache).ifPresent(masks -> {
                            BigInteger heteroMask = masks.heterozygousMask == null ? variantService.emptyBitmask() : masks.heterozygousMask;
                            BigInteger homoMask = masks.homozygousMask == null ? variantService.emptyBitmask() : masks.homozygousMask;
                            BigInteger orMasks = heteroMask.or(homoMask);
                            BigInteger andMasks = orMasks.and(patientsInScopeMask);
                            synchronized(matchingPatients) {
                                matchingPatients[0] = matchingPatients[0].or(andMasks);
                            }
                        });
                    });
                });
            }*/

            for (String variantSpec : variantsInScope) {
                Optional<VariantMasks> masksForVariant = variantService.getMasks(variantSpec, new VariantBucketHolder<>());
                if (masksForVariant.isEmpty()) {
                    log.info(variantSpec + " not found");
                }
                masksForVariant.ifPresent(masks -> {
                    BigInteger heteroMask = masks.heterozygousMask == null ? variantService.emptyBitmask() : masks.heterozygousMask;
                    BigInteger homoMask = masks.homozygousMask == null ? variantService.emptyBitmask() : masks.homozygousMask;
                    BigInteger orMasks = heteroMask.or(homoMask);
                    BigInteger andMasks = orMasks.and(patientsInScopeMask);
                    synchronized(matchingPatients) {
                        matchingPatients[0] = matchingPatients[0].or(andMasks);
                    }
                });
            }
            return matchingPatients[0];
        }else {
            log.error("No matches found for info filters.");
            return createMaskForPatientSet(new HashSet<>());
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
