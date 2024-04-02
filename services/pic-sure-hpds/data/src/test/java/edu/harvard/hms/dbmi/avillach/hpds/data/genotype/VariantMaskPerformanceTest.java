package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.math.BigInteger;
import java.util.Random;
import java.util.Set;

public class VariantMaskPerformanceTest {

    /**
     * This test shows the ideal maximum size for a sparse variant is between 5-10 items. The slight decrease in performance
     * is generally worth the drastic reduction in disks space
     */
    //@Test
    public void test() {
        VariantMaskBitmaskImpl mask100k = new VariantMaskBitmaskImpl(generateRandomBitmask(100_000));
        VariantMaskBitmaskImpl mask100k2 = new VariantMaskBitmaskImpl(generateRandomBitmask(100_000));
        VariantMaskBitmaskImpl mask1m = new VariantMaskBitmaskImpl(generateRandomBitmask(1_000_000));
        VariantMaskBitmaskImpl mask1m2 = new VariantMaskBitmaskImpl(generateRandomBitmask(1_000_000));
        VariantMaskSparseImpl sparseMask100k = new VariantMaskSparseImpl(Set.of(100, 200, 400, 50_000, 90_000));
        VariantMaskSparseImpl sparseMask100k2 = new VariantMaskSparseImpl(Set.of(100, 101, 200, 300, 400, 1000, 20_000, 30_000, 50_000, 90_000));
        VariantMaskSparseImpl sparseMask1m = new VariantMaskSparseImpl(Set.of(100, 200, 400, 50_000, 90_000, 300_000, 420_000, 555_555, 867_530, 999_999));

        long time = System.currentTimeMillis();
        for (int k = 0; k < 1000; k++) {
            VariantMask and = mask100k.union(mask100k2);
        }
        System.out.println(mask100k.getBitmask().bitLength() + " bitmask union completed in " + (System.currentTimeMillis() - time) + " ms");

        time = System.currentTimeMillis();
        for (int k = 0; k < 1000; k++) {
            VariantMask and = sparseMask100k.union(mask100k);
        }
        System.out.println(mask100k.getBitmask().bitLength() + " bitmask and " + sparseMask100k.patientIndexes.size() + " sparse union completed in " + (System.currentTimeMillis() - time) + " ms");

        time = System.currentTimeMillis();
        for (int k = 0; k < 1000; k++) {
            VariantMask and = sparseMask100k2.union(mask100k);
        }
        System.out.println(mask100k.getBitmask().bitLength() + " bitmask and " + sparseMask100k2.patientIndexes.size() + " sparse union completed in " + (System.currentTimeMillis() - time) + " ms");

        time = System.currentTimeMillis();
        for (int k = 0; k < 1000; k++) {
            VariantMask and = mask1m.union(mask1m2);
        }
        System.out.println(mask1m.getBitmask().bitLength() + " bitmask union completed in " + (System.currentTimeMillis() - time) + " ms");

        time = System.currentTimeMillis();
        for (int k = 0; k < 1000; k++) {
            VariantMask and = sparseMask100k.union(mask1m);
        }
        System.out.println(mask1m.getBitmask().bitLength() + " bitmask and " + sparseMask100k.patientIndexes.size() + " sparse union completed in " + (System.currentTimeMillis() - time) + " ms");

        time = System.currentTimeMillis();
        for (int k = 0; k < 1000; k++) {
            VariantMask and = sparseMask1m.union(mask1m);
        }
        System.out.println(mask1m.getBitmask().bitLength() + " bitmask and " + sparseMask1m.patientIndexes.size() + " sparse union completed in " + (System.currentTimeMillis() - time) + " ms");

    }

    private BigInteger generateRandomBitmask(int patients) {
        return new BigInteger(patients + 4, new Random());
    }

}
