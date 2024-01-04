package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

public class VariantMasksSerializationTest {

    @Test
    public void testFieldMaxLength() throws JsonProcessingException {
        VariantMasks variantMasks = new VariantMasks();

        StringBuilder homozygousStr = new StringBuilder();
        for (int i = 0 ; i < 5000; i++) {
            homozygousStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.homozygousMask = new BigInteger(homozygousStr.toString());

        StringBuilder heterozygousStr = new StringBuilder();
        for (int i = 0 ; i < 5000; i++) {
            heterozygousStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.heterozygousMask = new BigInteger(heterozygousStr.toString());

        StringBuilder homozygousNoCallStr = new StringBuilder();
        for (int i = 0 ; i < 5000; i++) {
            homozygousNoCallStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.homozygousNoCallMask = new BigInteger(homozygousNoCallStr.toString());

        StringBuilder heterozygousNoCallStr = new StringBuilder();
        for (int i = 0 ; i < 5000; i++) {
            heterozygousNoCallStr.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        variantMasks.heterozygousNoCallMask = new BigInteger(heterozygousNoCallStr.toString());

        ObjectMapper objectMapper = new ObjectMapper();
        String serialized = objectMapper.writeValueAsString(variantMasks);
        VariantMasks deserialized = objectMapper.readValue(serialized, VariantMasks.class);

        assertEquals(variantMasks, deserialized);
    }
}
