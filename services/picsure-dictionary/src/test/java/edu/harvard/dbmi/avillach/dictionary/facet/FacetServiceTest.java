package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FacetServiceTest {
    @MockBean
    private FacetRepository repository;

    @Autowired
    private FacetService subject;

    @Test
    void shouldGetFacets() {
        Filter filter = new Filter(List.of(), "");
        List<FacetCategory> expected =
            List.of(new FacetCategory("n", "d", "", List.of(new Facet("f_n", "f_d", "", 1, null, "n"))));
        Mockito.when(repository.getFacets(filter))
            .thenReturn(expected);

        List<FacetCategory> actual = subject.getFacets(filter);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacet() {
        Optional<Facet> expected = Optional.of(new Facet("n", "d", "", null, null, "c"));
        Mockito.when(repository.getFacet("c", "n"))
            .thenReturn(expected);

        Optional<Facet> actual = subject.facetDetails("c", "n");

        Assertions.assertEquals(expected, actual);
    }
}