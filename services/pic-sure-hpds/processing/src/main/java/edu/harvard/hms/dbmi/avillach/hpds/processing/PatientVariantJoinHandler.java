package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.*;
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

    public VariantMask getPatientIdsForIntersectionOfVariantSets(Set<Integer> patientSubset,
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
                return new VariantMaskSparseImpl(Set.of());
            }

            VariantMask[] matchingPatients = new VariantMask[] {new VariantMaskSparseImpl(Set.of())};

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


            //don't error on small result sets (make sure we have at least one element in each partition)
            int partitionSize = variantBucketsInScope.size() / Runtime.getRuntime().availableProcessors();
            List<List<List<String>>> variantBucketPartitions = Lists.partition(variantBucketsInScope, partitionSize > 0 ? partitionSize : 1);

            log.debug("found " + variantBucketsInScope.size() + " buckets and partitioned those into " + variantBucketPartitions.size() + " groups");

            VariantMask patientsInScopeMask = new VariantMaskBitmaskImpl(createMaskForPatientSet(patientsInScope));
            for(int x = 0;
                x < variantBucketPartitions.size() /*&& matchingPatients[0].bitCount() < patientsInScopeSize + 4*/;
                x++) {
                List<List<String>> variantBuckets = variantBucketPartitions.get(x);
                variantBuckets.parallelStream().forEach(variantBucket -> {
                    VariantBucketHolder<VariableVariantMasks> bucketCache = new VariantBucketHolder<>();
                    List<String> missingVariants = new ArrayList<>();
                    variantBucket.forEach(variantSpec -> {
                        Optional<VariableVariantMasks> variantMask = variantService.getMasks(variantSpec, bucketCache);
                        variantMask.ifPresentOrElse(masks -> {
                            VariantMask heterozygousMask = masks.heterozygousMask;
                            VariantMask homozygousMask = masks.homozygousMask;
                            if (heterozygousMask != null) {
                                synchronized(matchingPatients) {
                                    matchingPatients[0] = matchingPatients[0].union(heterozygousMask);
                                }
                            }
                            if (homozygousMask != null) {
                                synchronized(matchingPatients) {
                                    matchingPatients[0] = matchingPatients[0].union(homozygousMask);
                                }
                            }
                        }, () -> missingVariants.add(variantSpec));
                    });
                    if (!missingVariants.isEmpty()) {
                        log.debug(missingVariants.size() + " variant masks not found");
                        log.debug("Variants missing masks: " + Joiner.on(",").join( missingVariants.subList(0, Math.min(100, missingVariants.size()))));
                    }
                });
            }
            return matchingPatients[0].intersection(patientsInScopeMask);
        }else {
            // This will happen regularly, for example if someone is searching for a gene that is not on this partition
            log.debug("No matches found for info filters.");
            return new VariantMaskSparseImpl(Set.of());
        }
    }

    // todo: return VariantMask
    public BigInteger createMaskForPatientSet(Set<Integer> patientSet) {
        Set<Integer> patientSubset = patientSet;
        if (patientSet == null) {
            patientSubset = Arrays.asList(
                    variantService.getPatientIds()).stream().map((String id)->{
                return Integer.parseInt(id);}).collect(Collectors.toSet());
        }
        StringBuilder builder = new StringBuilder("11"); //variant bitmasks are bookended with '11'

        for (int i = variantService.getPatientIds().length - 1; i >= 0; i--) {
            Integer idInt = Integer.parseInt(variantService.getPatientIds()[i]);
            if(patientSubset.contains(idInt)){
                builder.append("1");
            } else {
                builder.append("0");
            }
        }
        builder.append("11"); // masks are bookended with '11' set this so we don't count those

        BigInteger patientMasks = new BigInteger(builder.toString(), 2);
        return patientMasks;
    }
}
