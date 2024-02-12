package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.base.Joiner;
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
            if(patientSubset != null) {
                patientsInScope = Sets.intersection(patientIds, patientSubset);
            } else {
                // for now, null means there were no phenotypic filters and all patients are eligible
                // this should be heavily tested/reconsidered for sharding genomic data by study
                patientsInScope = patientIds;
            }

            // If genomic data is sharded by studies, it may be possible that the node this is running in does not have genomic
            // data for any of the patients in the phenotypic query. In which case, we don't have to look for matching patients
            if (patientsInScope.isEmpty()) {
                return variantService.emptyBitmask();
            }

            BigInteger[] matchingPatients = new BigInteger[] {variantService.emptyBitmask()};

            Set<String> variantsInScope = intersectionOfInfoFilters.mapToVariantSpec(variantService.getVariantIndex());

            /*// todo: determine ideal ratio to bother with this
            if (patientsInScope.size() < variantService.getPatientIds().length) {
                variantsInScope = variantService.filterVariantSetForPatientSet(variantsInScope, patientsInScope);
            }*/
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
                variantBuckets.parallelStream().forEach(variantBucket -> {
                    VariantBucketHolder<VariantMasks> bucketCache = new VariantBucketHolder<>();
                    List<String> missingVariants = new ArrayList<>();
                    variantBucket.forEach(variantSpec -> {
                        Optional<VariantMasks> variantMask = variantService.getMasks(variantSpec, bucketCache);
                        variantMask.ifPresentOrElse(masks -> {
                            BigInteger heteroMask = masks.heterozygousMask == null ? variantService.emptyBitmask() : masks.heterozygousMask;
                            BigInteger homoMask = masks.homozygousMask == null ? variantService.emptyBitmask() : masks.homozygousMask;
                            BigInteger orMasks = heteroMask.or(homoMask);
                            BigInteger andMasks = orMasks.and(patientsInScopeMask);
                            synchronized(matchingPatients) {
                                matchingPatients[0] = matchingPatients[0].or(andMasks);
                            }
                        }, () -> missingVariants.add(variantSpec));
                    });
                    if (!missingVariants.isEmpty()) {
                        log.info(missingVariants.size() + " variant masks not found");
                        log.info("Variants missing masks: " + Joiner.on(",").join( missingVariants.subList(0, Math.min(100, missingVariants.size()))));
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
