package edu.harvard.hms.dbmi.avillach.hpds.processing;

import org.junit.Test;

import java.util.Set;
import static org.junit.Assert.*;

public class VariantIndexTest {


    @Test
    public void testSparseVariantUnion() {
        SparseVariantIndex sparseVariantIndex1 = new SparseVariantIndex(Set.of(1, 3, 5));
        SparseVariantIndex sparseVariantIndex2 = new SparseVariantIndex(Set.of(2, 4, 8));
        VariantIndex union = sparseVariantIndex1.union(sparseVariantIndex2);
        assertEquals(union.getClass(), SparseVariantIndex.class);
        assertEquals(Set.of(1, 2, 3, 4, 5, 8), ((SparseVariantIndex) union).getVariantIds());
    }

    @Test
    public void testSparseVariantIntersection() {
        SparseVariantIndex sparseVariantIndex1 = new SparseVariantIndex(Set.of(1, 3, 5, 7));
        SparseVariantIndex sparseVariantIndex2 = new SparseVariantIndex(Set.of(2, 3, 4, 5, 6));
        VariantIndex intersection = sparseVariantIndex1.intersection(sparseVariantIndex2);
        assertEquals(intersection.getClass(), SparseVariantIndex.class);
        assertEquals(Set.of(3, 5), ((SparseVariantIndex) intersection).getVariantIds());
    }
    @Test
    public void testDenseVariantUnion() {
        DenseVariantIndex denseVariantIndex1 = new DenseVariantIndex(new boolean[]{true, false, true, false});
        DenseVariantIndex denseVariantIndex2 = new DenseVariantIndex(new boolean[]{true, false, false, true});
        VariantIndex union = denseVariantIndex1.union(denseVariantIndex2);
        assertEquals(union.getClass(), DenseVariantIndex.class);
        assertArrayEquals(new boolean[]{true, false, true, true}, ((DenseVariantIndex) union).getVariantIndexMask());
    }
    @Test
    public void testDenseVariantIntersection() {
        DenseVariantIndex denseVariantIndex1 = new DenseVariantIndex(new boolean[]{true, false, true, false});
        DenseVariantIndex denseVariantIndex2 = new DenseVariantIndex(new boolean[]{true, false, false, true});
        VariantIndex intersection = denseVariantIndex1.intersection(denseVariantIndex2);
        assertEquals(intersection.getClass(), DenseVariantIndex.class);
        assertArrayEquals(new boolean[]{true, false, false, false}, ((DenseVariantIndex) intersection).getVariantIndexMask());
    }
    @Test
    public void testSparseAndDenseUnion() {
        SparseVariantIndex sparseVariantIndex1 = new SparseVariantIndex(Set.of(0, 2));
        DenseVariantIndex denseVariantIndex = new DenseVariantIndex(new boolean[] {true, true, false, false});
        VariantIndex union = sparseVariantIndex1.union(denseVariantIndex);
        assertEquals(union.getClass(), DenseVariantIndex.class);
        assertArrayEquals(new boolean[] {true, true, true, false}, ((DenseVariantIndex) union).getVariantIndexMask());
    }
    @Test
    public void testSparseAndDenseIntersection() {
        SparseVariantIndex sparseVariantIndex1 = new SparseVariantIndex(Set.of(0, 2));
        DenseVariantIndex denseVariantIndex = new DenseVariantIndex(new boolean[] {false, true, true, false});
        VariantIndex intersection = sparseVariantIndex1.intersection(denseVariantIndex);
        assertEquals(intersection.getClass(), SparseVariantIndex.class);
        assertEquals(Set.of(2), ((SparseVariantIndex) intersection).getVariantIds());
    }
}
