package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.remote.api.RemoteDictionaryAPI;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

@SpringBootTest
class DataRefreshServiceTest {

    @MockitoBean
    RemoteDictionaryRepository repository;

    @MockitoBean
    RemoteDictionaryAPI api;

    @Autowired
    DataRefreshService subject;

    RemoteDictionary bch = new RemoteDictionary("bch", "Boston Children's");

    @Test
    void shouldNotRefreshIfConceptsFails() {
        Mockito.when(api.fetchConcepts("bch")).thenReturn(Optional.empty());

        subject.refreshDictionary(bch);

        Mockito.verify(repository, Mockito.times(2)).dropValuesForSite("bch");
        Mockito.verify(repository, Mockito.times(2)).pruneHangingEntries();
        Mockito.verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldRefreshIfAllFetchesWork() {
        Mockito.when(api.fetchConcepts("bch")).thenReturn(Optional.of(List.of()));
        Mockito.when(api.fetchFacetCategories("bch")).thenReturn(Optional.of(List.of()));
        Mockito.when(api.fetchFacets("bch")).thenReturn(Optional.of(List.of()));
        Mockito.when(api.fetchFacetConceptPairs("bch")).thenReturn(Optional.of(List.of()));
        Mockito.when(api.fetchConceptMetas("bch")).thenReturn(Optional.of(List.of()));
        Mockito.when(api.fetchFacetMetas("bch")).thenReturn(Optional.of(List.of()));
        Mockito.when(api.fetchFacetCategoryMetas("bch")).thenReturn(Optional.of(List.of()));
        Mockito.when(api.fetchFacetMetas("bch")).thenReturn(Optional.of(List.of()));

        subject.refreshDictionary(bch);

        Mockito.verify(repository, Mockito.times(1)).dropValuesForSite("bch");
        Mockito.verify(repository, Mockito.times(1)).pruneHangingEntries();
        Mockito.verify(repository, Mockito.times(1)).addConceptsForSite("bch", List.of());
        Mockito.verify(repository, Mockito.times(1)).addFacetCategoriesForSite("bch", List.of());
        Mockito.verify(repository, Mockito.times(1)).addFacetsForSite("bch", List.of());
        Mockito.verify(repository, Mockito.times(1)).addConceptMetasForSite("bch", List.of());
        Mockito.verify(repository, Mockito.times(1)).addFacetConceptPairsForSite("bch", List.of());
        Mockito.verify(repository, Mockito.times(1)).addFacetMetasForSite("bch", List.of());
        Mockito.verify(repository, Mockito.times(1)).addFacetCategoryMetasForSite("bch", List.of());
        Mockito.verify(repository, Mockito.times(1)).addFacetMetasForSite("bch", List.of());
    }
}
