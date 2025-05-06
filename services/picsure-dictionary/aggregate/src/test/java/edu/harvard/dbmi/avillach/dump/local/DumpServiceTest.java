package edu.harvard.dbmi.avillach.dump.local;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;

@SpringBootTest
class DumpServiceTest {

    @Autowired
    DumpService subject;

    @MockBean
    DumpRepository repository;

    @Test
    void shouldCallAllMethods() {
        subject.dumpTable(DumpTable.ConceptNode);
        Mockito.verify(repository, Mockito.times(1)).getAllConcepts();
        subject.dumpTable(DumpTable.FacetCategory);
        Mockito.verify(repository, Mockito.times(1)).getAllFacetCategories();
        subject.dumpTable(DumpTable.Facet);
        Mockito.verify(repository, Mockito.times(1)).getAllFacets();
        subject.dumpTable(DumpTable.FacetConceptNode);
        Mockito.verify(repository, Mockito.times(1)).getAllFacetConceptPairs();
        subject.dumpTable(DumpTable.ConceptNodeMeta);
        Mockito.verify(repository, Mockito.times(1)).getAllConceptNodeMetas();
        subject.dumpTable(DumpTable.FacetCategoryMeta);
        Mockito.verify(repository, Mockito.times(1)).getAllFacetCategoryMetas();
        subject.dumpTable(DumpTable.FacetMeta);
        Mockito.verify(repository, Mockito.times(1)).getAllFacetMetas();
    }

    @Test
    void shouldGetLastUpdate() {
        Mockito.when(repository.getLastUpdated()).thenReturn(LocalDateTime.MIN);
        LocalDateTime actual = subject.getLastUpdate();
        Assertions.assertEquals(LocalDateTime.MIN, actual);
    }
}
