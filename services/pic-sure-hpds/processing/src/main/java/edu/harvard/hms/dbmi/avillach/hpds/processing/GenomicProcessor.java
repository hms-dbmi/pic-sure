package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface GenomicProcessor {
    BigInteger getPatientMask(DistributableQuery distributableQuery);

    Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask);

    BigInteger createMaskForPatientSet(Set<Integer> patientSubset);

    Collection<String> getVariantList(DistributableQuery distributableQuery);

    String[] getPatientIds();

    Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder);
}
