package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SpringBootTest
@ActiveProfiles("test")
class FacetServiceTest {
    @MockBean
    private FacetRepository repository;

    @Autowired
    private FacetService subject;

    @Test
    void shouldGetFacetsAlphabeticallyByDisplay() {
        Filter filter = new Filter(List.of(), "", List.of());
        FacetCategory A = new FacetCategory("a_cat", "A", "", List.of(new Facet("f_n", "f_d", "", "", 1, null, "n", null)));
        FacetCategory B = new FacetCategory("b_cat", "B", "", List.of());
        FacetCategory C = new FacetCategory("c_cat", "C", "", List.of());
        List<FacetCategory> stored = List.of(B, A, C);
        Mockito.when(repository.getFacets(filter)).thenReturn(stored);
        Mockito.when(repository.getFacetCategoryOrder(Mockito.anyList())).thenReturn(new HashMap<>());

        List<FacetCategory> actual = subject.getFacets(filter);

        Assertions.assertEquals(List.of(A, B, C), actual);
    }

    @Test
    void shouldGetFacetsAlphabeticallyByDisplay_caches() {
        Filter filter = new Filter(List.of(), "age", List.of());
        FacetCategory A = new FacetCategory("a_cat", "A", "", List.of(new Facet("f_n", "f_d", "", "", 1, null, "n", null)));
        FacetCategory B = new FacetCategory("b_cat", "B", "", List.of());
        FacetCategory C = new FacetCategory("c_cat", "C", "", List.of());
        List<FacetCategory> stored = List.of(B, A, C);
        Mockito.when(repository.getFacets(filter)).thenReturn(stored);
        Mockito.when(repository.getFacetCategoryOrder(Mockito.anyList())).thenReturn(new HashMap<>());

        List<FacetCategory> actual = subject.getFacets(filter);
        subject.getFacets(filter);

        Mockito.verify(repository, Mockito.times(1)).getFacets(filter);
    }

    @Test
    void shouldGetFacetsAlphabeticallyByOrderThenDisplay() {
        Filter filter = new Filter(List.of(), "asthma", List.of());
        FacetCategory A = new FacetCategory("a_cat", "A", "", List.of());
        FacetCategory B = new FacetCategory("b_cat", "B", "", List.of());
        FacetCategory C = new FacetCategory("c_cat", "C", "", List.of());
        List<FacetCategory> stored = List.of(B, A, C);
        Mockito.when(repository.getFacets(filter)).thenReturn(stored);
        Map<String, String> orders = new HashMap<>();
        orders.put("c_cat", "1");
        Mockito.when(repository.getFacetCategoryOrder(Mockito.anyList())).thenReturn(orders);

        List<FacetCategory> actual = subject.getFacets(filter);

        Assertions.assertEquals(List.of(C, A, B), actual);
    }

    @Test
    void shouldGetFacet() {
        Optional<Facet> expected = Optional.of(new Facet("n", "d", "", "", null, null, "c", Map.of("foo", "bar")));
        Mockito.when(repository.getFacet("c", "n")).thenReturn(expected);
        Mockito.when(repository.getFacetMeta("c", "n")).thenReturn(Map.of("foo", "bar"));

        Optional<Facet> actual = subject.facetDetails("c", "n");

        Assertions.assertEquals(expected, actual);
    }
}
