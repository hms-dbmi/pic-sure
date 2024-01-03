package edu.harvard.hms.dbmi.avillach.hpds.processing;

import com.google.common.collect.Sets;
import com.google.errorprone.annotations.Var;

import java.util.Set;
import java.util.stream.Collectors;

public abstract class VariantIndex {
    public abstract VariantIndex union(VariantIndex variantIndex);
    public abstract VariantIndex intersection(VariantIndex variantIndex);

    public abstract Set<String> mapToVariantSpec(String[] variantIndex);

    public abstract boolean isEmpty();

    protected VariantIndex union(SparseVariantIndex sparseVariantIndex, DenseVariantIndex denseVariantIndex) {
        boolean[] copy = new boolean[denseVariantIndex.getVariantIndexMask().length];
        System.arraycopy(denseVariantIndex.getVariantIndexMask(), 0, copy, 0, copy.length);
        sparseVariantIndex.getVariantIds().forEach(id -> copy[id] = true);
        return new DenseVariantIndex(copy);
    }


    protected VariantIndex intersection(SparseVariantIndex sparseVariantIndex, DenseVariantIndex denseVariantIndex) {
        Set<Integer> intersection = sparseVariantIndex.getVariantIds().stream()
                .filter(id -> denseVariantIndex.getVariantIndexMask()[id])
                .collect(Collectors.toSet());
        return new SparseVariantIndex(intersection);
    }

    public static VariantIndex empty() {
        return new SparseVariantIndex(Set.of());
    }
}
