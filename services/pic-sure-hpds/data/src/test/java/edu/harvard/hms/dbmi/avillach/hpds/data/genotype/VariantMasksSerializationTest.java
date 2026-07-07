package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

public class VariantMasksSerializationTest {

    @Test
    public void testFieldMaxLength() throws JsonProcessingException {
        VariantMasks variantMasks = new VariantMasks();

        StringBuilder homozygousStr = new StringBuilder();
        for (int i = 0 ; i < 50000; i++) {
            homozygousStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.homozygousMask = new BigInteger(homozygousStr.toString());

        StringBuilder heterozygousStr = new StringBuilder();
        for (int i = 0 ; i < 50000; i++) {
            heterozygousStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.heterozygousMask = new BigInteger(heterozygousStr.toString());

        StringBuilder homozygousNoCallStr = new StringBuilder();
        for (int i = 0 ; i < 50000; i++) {
            homozygousNoCallStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.homozygousNoCallMask = new BigInteger(homozygousNoCallStr.toString());

        StringBuilder heterozygousNoCallStr = new StringBuilder();
        for (int i = 0 ; i < 50000; i++) {
            heterozygousNoCallStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.heterozygousNoCallMask = new BigInteger(heterozygousNoCallStr.toString());

        ObjectMapper objectMapper = new ObjectMapper();
        String serialized = objectMapper.writeValueAsString(variantMasks);
        VariantMasks deserialized = objectMapper.readValue(serialized, VariantMasks.class);

        assertEquals(variantMasks, deserialized);
    }



    @Test
    public void testVariableVariantMasks() throws JsonProcessingException {
        VariableVariantMasks variableVariantMasks = new VariableVariantMasks();
        variableVariantMasks.heterozygousMask = new VariantMaskSparseImpl(Set.of(1, 2, 3));
        variableVariantMasks.homozygousMask = new VariantMaskBitmaskImpl(new BigInteger("1101010101010101011"));
        variableVariantMasks.heterozygousNoCallMask = new VariantMaskSparseImpl(Set.of());
        variableVariantMasks.homozygousNoCallMask = null;

        ObjectMapper objectMapper = new ObjectMapper();
        String serialized = objectMapper.writeValueAsString(variableVariantMasks);
        System.out.println(serialized);
        VariableVariantMasks deserialized = objectMapper.readValue(serialized, VariableVariantMasks.class);

        assertEquals(variableVariantMasks, deserialized);
    }
}
