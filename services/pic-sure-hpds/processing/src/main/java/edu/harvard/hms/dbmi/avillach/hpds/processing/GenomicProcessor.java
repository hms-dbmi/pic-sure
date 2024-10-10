package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.*;

public interface GenomicProcessor {
    Mono<VariantMask> getPatientMask(DistributableQuery distributableQuery);

    Set<Integer> patientMaskToPatientIdSet(VariantMask patientMask);

    VariantMask createMaskForPatientSet(Set<Integer> patientSubset);

    Mono<Set<String>> getVariantList(DistributableQuery distributableQuery);

    List<String> getPatientIds();

    Optional<VariableVariantMasks> getMasks(String path, VariantBucketHolder<VariableVariantMasks> variantMasksVariantBucketHolder);

    Set<String> getInfoStoreColumns();

    Set<String> getInfoStoreValues(String conceptPath);

    List<InfoColumnMeta> getInfoColumnMeta();

    Map<String, Set<String>> getVariantMetadata(Collection<String> variantList);
}
