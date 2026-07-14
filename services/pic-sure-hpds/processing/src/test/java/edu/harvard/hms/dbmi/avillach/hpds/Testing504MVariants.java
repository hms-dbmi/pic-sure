package edu.harvard.hms.dbmi.avillach.hpds;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Random;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

public class Testing504MVariants {

    @Test
    public void test() {
        BigInteger mask = generateRandomBitmask();
        BigInteger mask2 = generateRandomBitmask();
        BigInteger mask3 = generateRandomBitmask();
        System.out.println(mask.bitCount());
        System.out.println(mask2.bitCount());
        System.out.println(mask2.and(mask).bitCount());
        System.out.println(mask3.bitCount());
        System.out.println(mask2.and(mask3).bitCount());
    }

    private BigInteger generateRandomBitmask() {
        int[] ints = new int[504000000 / 32];
        Random r = new Random();
        for (int x = 0; x < ints.length; x++) {
            ints[x] = r.nextInt();
        }
        BigInteger mask = null;;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();) {
            ByteBuffer buf = ByteBuffer.allocate(4);
            for (int x : ints) {
                buf.clear();
                buf.putInt(x);
                out.write(buf.array());
            }
            mask = new BigInteger(out.toByteArray());
            System.out.println(out.size());

        } catch (IOException e1) {
            e1.printStackTrace();
        }
        return mask;
    }

}
