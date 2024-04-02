package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface GenomicProcessor {
    Mono<VariantMask> getPatientMask(DistributableQuery distributableQuery);

    Set<Integer> patientMaskToPatientIdSet(VariantMask patientMask);

    VariantMask createMaskForPatientSet(Set<Integer> patientSubset);

    Mono<Collection<String>> getVariantList(DistributableQuery distributableQuery);

    List<String> getPatientIds();

    Optional<VariableVariantMasks> getMasks(String path, VariantBucketHolder<VariableVariantMasks> variantMasksVariantBucketHolder);

    Set<String> getInfoStoreColumns();

    Set<String> getInfoStoreValues(String conceptPath);

    List<InfoColumnMeta> getInfoColumnMeta();
}
