package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.caching.VariantBucketHolder;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class GenomicProcessorNoOp implements GenomicProcessor {
    @Override
    public Mono<BigInteger> getPatientMask(DistributableQuery distributableQuery) {
        return null;
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(BigInteger patientMask) {
        return null;
    }

    @Override
    public BigInteger createMaskForPatientSet(Set<Integer> patientSubset) {
        return null;
    }

    @Override
    public Mono<Collection<String>> getVariantList(DistributableQuery distributableQuery) {
        return null;
    }

    @Override
    public List<String> getPatientIds() {
        return null;
    }

    @Override
    public Optional<VariantMasks> getMasks(String path, VariantBucketHolder<VariantMasks> variantMasksVariantBucketHolder) {
        return Optional.empty();
    }

    @Override
    public Set<String> getInfoStoreColumns() {
        return null;
    }

    @Override
    public Set<String> getInfoStoreValues(String conceptPath) {
        return null;
    }

    @Override
    public List<InfoColumnMeta> getInfoColumnMeta() {
        return null;
    }
}
