package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.internal.verification.Times;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

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
    void shouldGetFacets() {
        Filter filter = new Filter(List.of(), "", List.of());
        List<FacetCategory> expected =
            List.of(new FacetCategory("n", "d", "", List.of(new Facet("f_n", "f_d", "", "", 1, null, "n", null))));
        Mockito.when(repository.getFacets(filter)).thenReturn(expected);

        List<FacetCategory> actual = subject.getFacets(filter);
        subject.getFacets(filter);
        Mockito.verify(repository, Mockito.times(1)).getFacets(filter);

        Assertions.assertEquals(expected, actual);
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
