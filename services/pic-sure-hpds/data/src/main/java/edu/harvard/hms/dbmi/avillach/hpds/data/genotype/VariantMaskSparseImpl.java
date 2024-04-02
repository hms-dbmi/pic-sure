package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class VariantMaskSparseImpl implements VariantMask {

    @JsonProperty("p")
    protected Set<Integer> patientIndexes;

    public VariantMaskSparseImpl(@JsonProperty("p") Set<Integer> patientIndexes) {
        this.patientIndexes = patientIndexes;
    }

    public Set<Integer> getPatientIndexes() {
        return patientIndexes;
    }

    @Override
    public VariantMask intersection(VariantMask variantMask) {
        return new VariantMaskSparseImpl(this.patientIndexes.stream()
                .filter(variantMask::testBit)
                .collect(Collectors.toSet()));
    }

    @Override
    public VariantMask union(VariantMask variantMask) {
        if (variantMask instanceof VariantMaskBitmaskImpl) {
            return union((VariantMaskBitmaskImpl) variantMask);
        } else if (variantMask instanceof  VariantMaskSparseImpl) {
            return union((VariantMaskSparseImpl) variantMask);
        } else {
            throw new RuntimeException("Unknown VariantMask implementation");
        }
    }

    @Override
    public boolean testBit(int bit) {
        return patientIndexes.contains(bit);
    }

    @Override
    public int bitCount() {
        return patientIndexes.size();
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(List<String> patientIds) {
        return patientIndexes.stream()
                .map(patientIds::get)
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    private VariantMask union(VariantMaskSparseImpl variantMask) {
        HashSet<Integer> union = new HashSet<>(variantMask.patientIndexes);
        union.addAll(this.patientIndexes);
        return new VariantMaskSparseImpl(union);
    }

    private VariantMask union(VariantMaskBitmaskImpl variantMaskBitmask) {
        BigInteger union = variantMaskBitmask.bitmask;
        for (Integer patientId : this.patientIndexes) {
            union = union.setBit(patientId + 2);
        }
        return new VariantMaskBitmaskImpl(union);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VariantMaskSparseImpl that = (VariantMaskSparseImpl) o;
        return Objects.equals(patientIndexes, that.patientIndexes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patientIndexes);
    }
}
