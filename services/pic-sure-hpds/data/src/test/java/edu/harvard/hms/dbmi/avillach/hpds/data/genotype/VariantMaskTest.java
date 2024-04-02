package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VariantMaskTest {

    @Test
    public void intersection_bitmaskVsBitmask() {
        VariantMask mask1 = new VariantMaskBitmaskImpl(new BigInteger("111001100011", 2));
        VariantMask mask2 = new VariantMaskBitmaskImpl(new BigInteger("111010010011", 2));
        VariantMask expected = new VariantMaskBitmaskImpl(new BigInteger("111000000011", 2));

        assertEquals(expected, mask1.intersection(mask2));
    }
    @Test
    public void intersection_bitmaskVsSparse() {
        // this is essentially a mask for patients 0, 2, 3, 7 (there is 11 padding on both ends)
        VariantMask mask1 = new VariantMaskBitmaskImpl(new BigInteger("111000110111", 2));
        VariantMask mask2 = new VariantMaskSparseImpl(Set.of(0, 3, 6));
        VariantMask expected = new VariantMaskSparseImpl(Set.of(0, 3));

        assertEquals(expected, mask1.intersection(mask2));
    }
    @Test
    public void intersection_sparseVsBitmask() {
        VariantMask mask1 = new VariantMaskSparseImpl(Set.of(4, 7));
        VariantMask mask2 = new VariantMaskBitmaskImpl(new BigInteger("110111110111", 2));
        VariantMask expected = new VariantMaskSparseImpl(Set.of(4));

        assertEquals(expected, mask1.intersection(mask2));
    }

    @Test
    public void intersection_sparseVsSparse() {
        VariantMask mask1 = new VariantMaskSparseImpl(Set.of(0, 2, 4, 6));
        VariantMask mask2 = new VariantMaskSparseImpl(Set.of(0, 1, 3, 5, 7));
        VariantMask expected = new VariantMaskSparseImpl(Set.of(0));

        assertEquals(expected, mask1.intersection(mask2));
    }

    @Test
    public void union_bitmaskVsBitmask() {
        VariantMask mask1 = new VariantMaskBitmaskImpl(new BigInteger("111001100011", 2));
        VariantMask mask2 = new VariantMaskBitmaskImpl(new BigInteger("111010010011", 2));
        VariantMask expected = new VariantMaskBitmaskImpl(new BigInteger("111011110011", 2));

        assertEquals(expected, mask1.union(mask2));
    }

    @Test
    public void union_bitmaskVsSparse() {
        // this is essentially a mask for patients 0, 2, 3, 7 (there is 11 padding on both ends)
        VariantMask mask1 = new VariantMaskBitmaskImpl(new BigInteger("111000110111", 2));
        VariantMask mask2 = new VariantMaskSparseImpl(Set.of(0, 3, 6));
        VariantMask expected = new VariantMaskBitmaskImpl(new BigInteger("111100110111", 2));

        assertEquals(expected, mask1.union(mask2));
    }

    @Test
    public void union_sparseVsBitmask() {
        VariantMask mask1 = new VariantMaskSparseImpl(Set.of(4, 7));
        VariantMask mask2 = new VariantMaskBitmaskImpl(new BigInteger("110111110111", 2));
        VariantMask expected = new VariantMaskBitmaskImpl(new BigInteger("111111110111", 2));

        assertEquals(expected, mask1.union(mask2));
    }

    @Test
    public void union_sparseVsSparse() {
        VariantMask mask1 = new VariantMaskSparseImpl(Set.of(0, 2, 4, 6));
        VariantMask mask2 = new VariantMaskSparseImpl(Set.of(1, 5, 7));
        VariantMask expected = new VariantMaskSparseImpl(Set.of(0, 1, 2, 4, 5, 6, 7));

        assertEquals(expected, mask1.union(mask2));
    }
}
