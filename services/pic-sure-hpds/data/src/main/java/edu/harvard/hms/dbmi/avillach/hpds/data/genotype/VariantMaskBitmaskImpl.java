package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class VariantMaskBitmaskImpl implements VariantMask {


    @JsonProperty("mask")
    @JsonSerialize(using = ToStringSerializer.class)
    protected final BigInteger bitmask;

    public BigInteger getBitmask() {
        return bitmask;
    }

    @JsonCreator
    public VariantMaskBitmaskImpl(@JsonProperty("mask") String stringMask) {
        this.bitmask = new BigInteger(stringMask);
    }

    public VariantMaskBitmaskImpl(BigInteger bitmask) {
        this.bitmask = bitmask;
    }

    @Override
    public VariantMask intersection(VariantMask variantMask) {
        if (variantMask instanceof VariantMaskBitmaskImpl) {
            return intersection((VariantMaskBitmaskImpl) variantMask);
        } else if (variantMask instanceof  VariantMaskSparseImpl) {
            return variantMask.intersection(this);
        } else {
            throw new RuntimeException("Unknown VariantMask implementation");
        }
    }

    @Override
    public VariantMask union(VariantMask variantMask) {
        if (variantMask instanceof VariantMaskBitmaskImpl) {
            return union((VariantMaskBitmaskImpl) variantMask);
        } else if (variantMask instanceof  VariantMaskSparseImpl) {
            return variantMask.union(this);
        } else {
            throw new RuntimeException("Unknown VariantMask implementation");
        }
    }

    @Override
    public boolean testBit(int bit) {
        return bitmask.testBit(bit + 2);
    }

    @Override
    public int bitCount() {
        return bitmask.bitCount();
    }

    @Override
    public Set<Integer> patientMaskToPatientIdSet(List<String> patientIds) {
        Set<Integer> ids = new HashSet<>();
        for(int x = 0;x < bitmask.bitLength()-4;x++) {
            if(testBit(x)) {
                String patientId = patientIds.get(x).trim();
                ids.add(Integer.parseInt(patientId));
            }
        }
        return ids;
    }

    @Override
    @JsonIgnore
    public boolean isEmpty() {
        // because the bitmasks are padded with 11 on each end
        return bitmask.bitCount() <= 4;
    }

    private VariantMask union(VariantMaskBitmaskImpl variantMaskBitmask) {
        return new VariantMaskBitmaskImpl(variantMaskBitmask.bitmask.or(this.bitmask));
    }
    private VariantMask intersection(VariantMaskBitmaskImpl variantMaskBitmask) {
        // we could consider using a sparse variant index here if we are ever going to be storing the
        // result of this anywhere
        return new VariantMaskBitmaskImpl(variantMaskBitmask.bitmask.and(this.bitmask));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VariantMaskBitmaskImpl that = (VariantMaskBitmaskImpl) o;
        return Objects.equals(bitmask, that.bitmask);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bitmask);
    }

    @Override
    public String toString() {
        return "VariantMaskBitmaskImpl{" +
                "bitmask=" + bitmask.toString() +
                '}';
    }
}
