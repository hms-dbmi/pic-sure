package edu.harvard.dbmi.avillach.visualization.processing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoricalAggregationServiceTest {

    private CategoricalAggregationService service;

    @BeforeEach
    void setUp() {
        service = new CategoricalAggregationService(7);
    }

    @Test
    void aggregateTopN_moreThanMaxCategories_createsOtherBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Cat7", 40);
        categories.put("Cat8", 30);
        categories.put("Cat9", 20);

        Map<String, Integer> result = service.aggregateTopN(categories);

        assertTrue(result.containsKey("Other"));
        assertEquals(50, result.get("Other"));
    }

    @Test
    void aggregateTopN_exactlyOneOverMax_createsOtherBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Cat7", 40);
        categories.put("Cat8", 30);

        Map<String, Integer> result = service.aggregateTopN(categories);

        assertTrue(result.containsKey("Other"));
        assertEquals(30, result.get("Other"));
        assertEquals(8, result.size());
    }

    @Test
    void aggregateTopN_exactlyAtMax_noOtherBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Cat7", 40);

        Map<String, Integer> result = service.aggregateTopN(categories);

        assertFalse(result.containsKey("Other"));
        assertEquals(7, result.size());
    }

    @Test
    void aggregateTopN_truncatesLongKeys() {
        String longKey = "a".repeat(60);
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put(longKey, 100);

        Map<String, Integer> result = service.aggregateTopN(categories);

        assertFalse(result.containsKey(longKey));
        assertTrue(
            result.keySet().iterator().next().length() <= 48,
            "Long keys should be truncated"
        );
    }

    @Test
    void aggregateTopN_nullInput_returnsEmptyMap() {
        Map<String, Integer> result = service.aggregateTopN(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void aggregateTopN_emptyInput_returnsEmptyMap() {
        Map<String, Integer> result = service.aggregateTopN(new java.util.LinkedHashMap<>());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void aggregateTopN_singleLongKeyNoCollision_usesSimpleTruncation() {
        String longKey = "a".repeat(60);
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put(longKey, 100);

        Map<String, Integer> result = service.aggregateTopN(categories);

        String adjusted = result.keySet().iterator().next();
        assertEquals("a".repeat(45) + "...", adjusted);
        assertEquals(100, result.get(adjusted));
    }

    @Test
    void aggregateTopN_realCategoryNamedOther_sumsWithTailBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Other", 40);
        categories.put("Cat8", 30);
        categories.put("Cat9", 20);

        Map<String, Integer> result = service.aggregateTopN(categories);

        assertEquals(90, result.get("Other"), "Real 'Other' category must sum with tail bucket, not be overwritten");
        assertEquals(
            categories.values().stream().mapToInt(Integer::intValue).sum(),
            result.values().stream().mapToInt(Integer::intValue).sum(),
            "Total count must be preserved"
        );
    }

    @Test
    void aggregateTopN_manyKeysSharingFullPrefix_doesNotCrashLoop() {
        // Pre-fix: createAdjustedKey's disambiguation loop crashes with
        // StringIndexOutOfBoundsException around the 38th key because the
        // character budget (MAX_LABEL_LENGTH - 3 - countFromEnd) goes negative
        // once enough variants are already in the seen-keys set.
        CategoricalAggregationService largeService = new CategoricalAggregationService(50);
        Map<String, Integer> categories = new LinkedHashMap<>();
        String prefix = "a".repeat(45);
        String suffix = "a".repeat(42);
        for (int i = 0; i < 50; i++) {
            String key = prefix + (char) ('A' + i) + suffix;
            categories.put(key, 1000 - i);
        }

        Map<String, Integer> result = largeService.aggregateTopN(categories);

        assertEquals(50, result.size(), "All 50 keys should produce distinct adjusted labels");
        assertEquals(
            categories.values().stream().mapToInt(Integer::intValue).sum(),
            result.values().stream().mapToInt(Integer::intValue).sum(),
            "Total counts must not be lost during disambiguation"
        );
    }
}
