package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        defaultImpl = VariantMaskBitmaskImpl.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = VariantMaskBitmaskImpl.class, name = "b"),
        @JsonSubTypes.Type(value = VariantMaskSparseImpl.class, name = "s")
})
public interface VariantMask {

    VariantMask intersection(VariantMask variantMask);

    VariantMask union(VariantMask variantMask);

    boolean testBit(int bit);

    int bitCount();

    static VariantMask emptyInstance() {
        return new VariantMaskSparseImpl(Set.of());
    }

    Set<Integer> patientMaskToPatientIdSet(List<String> patientIds);

    boolean isEmpty();
}
