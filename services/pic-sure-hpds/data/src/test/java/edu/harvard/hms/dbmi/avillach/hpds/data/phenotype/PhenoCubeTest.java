package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PhenoCubeTest {

    @Test
    void shouldGetValuesForKeys() {
        KeyAndValue[] sortedByKey =
            {new KeyAndValue<>(1, "a"), new KeyAndValue<>(1, "b"), new KeyAndValue<>(2, "c"), new KeyAndValue<>(3, "d"),};
        PhenoCube<String> subject = new PhenoCube<>("phill the phenocube", String.class);
        subject.setSortedByKey(sortedByKey);

        Set<Integer> patientIds = new LinkedHashSet<>(List.of(3, 2, 1));
        List<KeyAndValue<String>> actual = subject.getValuesForKeys(patientIds);
        List<KeyAndValue<String>> expected = List.of(sortedByKey);

        assertEquals(expected, actual);
    }

    @Test
    public void merge_validKeyAndValues_shouldMergeAndMaintainSort() {
        PhenoCube<String> phenoCube1 = new PhenoCube<>("name", String.class);
        PhenoCube<String> phenoCube2 = new PhenoCube<>("name", String.class);

        phenoCube1.setSortedByKey(
            new KeyAndValue[] {new KeyAndValue<>(1, "Lionel"), new KeyAndValue<>(3, "Kyllian"), new KeyAndValue<>(4, "Harry"),
                new KeyAndValue<>(7, "Erling"),}
        );
        phenoCube2.setSortedByKey(
            new KeyAndValue[] {new KeyAndValue<>(2, "Rodri"), new KeyAndValue<>(5, "Jude"), new KeyAndValue<>(6, "Michael"),
                new KeyAndValue<>(8, "Enzo"),}
        );

        KeyAndValue[] expected = {new KeyAndValue<>(1, "Lionel"), new KeyAndValue<>(2, "Rodri"), new KeyAndValue<>(3, "Kyllian"),
            new KeyAndValue<>(4, "Harry"), new KeyAndValue<>(5, "Jude"), new KeyAndValue<>(6, "Michael"), new KeyAndValue<>(7, "Erling"),
            new KeyAndValue<>(8, "Enzo"),};
        assertArrayEquals(expected, phenoCube1.merge(phenoCube2).getSortedByKey());
        assertArrayEquals(expected, phenoCube2.merge(phenoCube1).getSortedByKey());
    }

    @Test
    public void merge_validKeyAndValuesWithOverlapAndMultiple_shouldMergeAndMaintainSort() {
        PhenoCube<Double> phenoCube1 = new PhenoCube<>("weight", Double.class);
        PhenoCube<Double> phenoCube2 = new PhenoCube<>("weight", Double.class);

        KeyAndValue[] sortedByKey1 = {new KeyAndValue<>(1, 150), new KeyAndValue<>(1, 200), new KeyAndValue<>(10, 200),
            new KeyAndValue<>(100, 100), new KeyAndValue<>(1000, 100),};
        phenoCube1.setSortedByKey(sortedByKey1);
        KeyAndValue[] sortedByKey2 = {new KeyAndValue<>(1, 125), new KeyAndValue<>(100, 175), new KeyAndValue<>(500, 175),
            new KeyAndValue<>(1000, 150), new KeyAndValue<>(1000, 100),};
        phenoCube2.setSortedByKey(sortedByKey2);

        KeyAndValue<Double>[] mergedSortedByKey = phenoCube1.merge(phenoCube2).getSortedByKey();
        // verify the merged result contains all the key values from both pheno cubes
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey1).toList()));
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey2).toList()));
        // verify the merged result is sorted
        for (int i = 1; i < mergedSortedByKey.length; i++) {
            assertTrue(mergedSortedByKey[i].getKey() >= mergedSortedByKey[i - 1].getKey());
        }

        mergedSortedByKey = phenoCube2.merge(phenoCube1).getSortedByKey();
        // verify the merged result contains all the key values from both pheno cubes
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey1).toList()));
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey2).toList()));
        // verify the merged result is sorted
        for (int i = 1; i < mergedSortedByKey.length; i++) {
            assertTrue(mergedSortedByKey[i].getKey() >= mergedSortedByKey[i - 1].getKey());
        }
    }


    @Test
    public void merge_validKeyAndValuesWithNonOverlappingRanges_shouldMergeAndMaintainSort() {
        PhenoCube<Double> phenoCube1 = new PhenoCube<>("weight", Double.class);
        PhenoCube<Double> phenoCube2 = new PhenoCube<>("weight", Double.class);

        KeyAndValue[] sortedByKey1 = {new KeyAndValue<>(1, 150), new KeyAndValue<>(1, 200), new KeyAndValue<>(10, 200),
            new KeyAndValue<>(100, 100), new KeyAndValue<>(100, 100),};
        phenoCube1.setSortedByKey(sortedByKey1);
        KeyAndValue[] sortedByKey2 = {new KeyAndValue<>(1000, 125), new KeyAndValue<>(1000, 175), new KeyAndValue<>(5000, 175),
            new KeyAndValue<>(10000, 150), new KeyAndValue<>(10000, 100),};
        phenoCube2.setSortedByKey(sortedByKey2);

        KeyAndValue<Double>[] mergedSortedByKey = phenoCube1.merge(phenoCube2).getSortedByKey();
        // verify the merged result contains all the key values from both pheno cubes
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey1).toList()));
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey2).toList()));
        // verify the merged result is sorted
        for (int i = 1; i < mergedSortedByKey.length; i++) {
            assertTrue(mergedSortedByKey[i].getKey() >= mergedSortedByKey[i - 1].getKey());
        }

        mergedSortedByKey = phenoCube2.merge(phenoCube1).getSortedByKey();
        // verify the merged result contains all the key values from both pheno cubes
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey1).toList()));
        assertTrue(Arrays.stream(mergedSortedByKey).toList().containsAll(Arrays.stream(sortedByKey2).toList()));
        // verify the merged result is sorted
        for (int i = 1; i < mergedSortedByKey.length; i++) {
            assertTrue(mergedSortedByKey[i].getKey() >= mergedSortedByKey[i - 1].getKey());
        }
    }

    @Test
    public void merge_validCategoryMaps_mergeAll() {
        PhenoCube<String> phenoCube1 = new PhenoCube<>("name", String.class);
        PhenoCube<String> phenoCube2 = new PhenoCube<>("name", String.class);

        phenoCube1.setSortedByKey(new KeyAndValue[] {});
        phenoCube2.setSortedByKey(new KeyAndValue[] {});

        TreeMap<String, TreeSet<Integer>> categoryMap1 = new TreeMap<>();
        categoryMap1.put("Spock", new TreeSet<>(Arrays.asList(1, 2, 3)));
        categoryMap1.put("McCoy", new TreeSet<>(Arrays.asList(4, 5, 6)));
        categoryMap1.put("Uhura", new TreeSet<>(Arrays.asList(12, 13, 14)));
        phenoCube1.setCategoryMap(categoryMap1);

        TreeMap<String, TreeSet<Integer>> categoryMap2 = new TreeMap<>();
        categoryMap2.put("Uhura", new TreeSet<>(Arrays.asList(10, 11, 12)));
        categoryMap2.put("Sulu", new TreeSet<>(Arrays.asList(1, 2, 3)));
        categoryMap2.put("Chekov", new TreeSet<>(Arrays.asList(100, 200, 300)));
        phenoCube2.setCategoryMap(categoryMap2);

        TreeMap<String, TreeSet<Integer>> mergedCategoryMap = phenoCube1.merge(phenoCube2).getCategoryMap();
        assertEquals(5, mergedCategoryMap.size());
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3)), mergedCategoryMap.get("Spock"));
        assertEquals(new TreeSet<>(Arrays.asList(4, 5, 6)), mergedCategoryMap.get("McCoy"));
        assertEquals(new TreeSet<>(Arrays.asList(10, 11, 12, 13, 14)), mergedCategoryMap.get("Uhura"));
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3)), mergedCategoryMap.get("Sulu"));
        assertEquals(new TreeSet<>(Arrays.asList(100, 200, 300)), mergedCategoryMap.get("Chekov"));

        mergedCategoryMap = phenoCube2.merge(phenoCube1).getCategoryMap();
        assertEquals(5, mergedCategoryMap.size());
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3)), mergedCategoryMap.get("Spock"));
        assertEquals(new TreeSet<>(Arrays.asList(4, 5, 6)), mergedCategoryMap.get("McCoy"));
        assertEquals(new TreeSet<>(Arrays.asList(10, 11, 12, 13, 14)), mergedCategoryMap.get("Uhura"));
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3)), mergedCategoryMap.get("Sulu"));
        assertEquals(new TreeSet<>(Arrays.asList(100, 200, 300)), mergedCategoryMap.get("Chekov"));
    }


    @Test
    public void merge_oneEmptyCategoryMap_mergeAll() {
        PhenoCube<String> phenoCube1 = new PhenoCube<>("name", String.class);
        PhenoCube<String> phenoCube2 = new PhenoCube<>("name", String.class);

        phenoCube1.setSortedByKey(new KeyAndValue[] {});
        phenoCube2.setSortedByKey(new KeyAndValue[] {});

        TreeMap<String, TreeSet<Integer>> categoryMap1 = new TreeMap<>();
        categoryMap1.put("Spock", new TreeSet<>(Arrays.asList(1, 2, 3)));
        categoryMap1.put("McCoy", new TreeSet<>(Arrays.asList(4, 5, 6)));
        categoryMap1.put("Uhura", new TreeSet<>(Arrays.asList(12, 13, 14)));
        phenoCube1.setCategoryMap(categoryMap1);

        TreeMap<String, TreeSet<Integer>> mergedCategoryMap = phenoCube1.merge(phenoCube2).getCategoryMap();
        assertEquals(3, mergedCategoryMap.size());
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3)), mergedCategoryMap.get("Spock"));
        assertEquals(new TreeSet<>(Arrays.asList(4, 5, 6)), mergedCategoryMap.get("McCoy"));
        assertEquals(new TreeSet<>(Arrays.asList(12, 13, 14)), mergedCategoryMap.get("Uhura"));

        mergedCategoryMap = phenoCube2.merge(phenoCube1).getCategoryMap();
        assertEquals(3, mergedCategoryMap.size());
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3)), mergedCategoryMap.get("Spock"));
        assertEquals(new TreeSet<>(Arrays.asList(4, 5, 6)), mergedCategoryMap.get("McCoy"));
        assertEquals(new TreeSet<>(Arrays.asList(12, 13, 14)), mergedCategoryMap.get("Uhura"));
    }
}
