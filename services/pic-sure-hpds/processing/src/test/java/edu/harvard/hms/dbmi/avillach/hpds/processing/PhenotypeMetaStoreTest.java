package edu.harvard.hms.dbmi.avillach.hpds.processing;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhenotypeMetaStoreTest {

    private TreeMap<String, ColumnMeta> metaStore;

    private TreeSet<Integer> patientIds;

    private PhenotypeMetaStore phenotypeMetaStore;

    @BeforeEach
    public void setup() {
        metaStore = new TreeMap<>();
        metaStore.put("\\study1\\demographics\\age\\", new ColumnMeta().setName("age"));
        metaStore.put("\\study1\\demographics\\sex\\", new ColumnMeta().setName("sex"));
        metaStore.put("\\study2\\demographics\\age\\", new ColumnMeta().setName("age"));
        metaStore.put("\\study2\\demographics\\sex\\", new ColumnMeta().setName("sex"));

        patientIds = new TreeSet<>();
        phenotypeMetaStore = new PhenotypeMetaStore(metaStore, patientIds, 500);
    }

    @Test
    public void loadChildConceptPaths_matchingConcepts_shouldReturnConcepts() {
        Set<String> childConceptPaths = phenotypeMetaStore.loadChildConceptPaths("\\study1\\demographics\\");
        assertEquals(Set.of("\\study1\\demographics\\age\\", "\\study1\\demographics\\sex\\"), childConceptPaths);
    }

    @Test
    public void loadChildConceptPaths_noMatchingConcepts_shouldReturnNoConcepts() {
        Set<String> childConceptPaths = phenotypeMetaStore.loadChildConceptPaths("\\study3\\demographics\\");
        assertEquals(Set.of(), childConceptPaths);
    }

    @Test
    public void getChildConceptPaths_multipleCalls_shouldCacheResults() {
        TreeMap<String, ColumnMeta> metaStore = mock(TreeMap.class);
        when(metaStore.keySet()).thenReturn(
            Set.of(
                "\\study1\\demographics\\age\\", "\\study1\\demographics\\sex\\", "\\study2\\demographics\\age\\",
                "\\study2\\demographics\\sex\\"
            )
        );
        phenotypeMetaStore = new PhenotypeMetaStore(metaStore, patientIds, 500);

        for (int k = 0; k < 5; k++) {
            Set<String> childConceptPaths = phenotypeMetaStore.getChildConceptPaths("\\study1\\demographics\\");
            assertEquals(Set.of("\\study1\\demographics\\age\\", "\\study1\\demographics\\sex\\"), childConceptPaths);
        }
        verify(metaStore, times(1)).keySet();
    }
}
