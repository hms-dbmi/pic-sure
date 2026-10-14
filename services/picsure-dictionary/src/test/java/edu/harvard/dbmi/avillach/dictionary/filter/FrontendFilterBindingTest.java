package edu.harvard.dbmi.avillach.dictionary.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dictionary.concept.ConceptService;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.facet.FacetService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the filter the frontend posts to the concept search and the facet count. It sends back the facet objects it received, with the
 * {@code categoryRef} and {@code parentRef} keys it added to them for its own use, so a facet must bind with keys the record does not
 * declare.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FrontendFilterBindingTest {

    private static final String FRONTEND_FILTER = """
        {"facets":[{"name":"phs000007","display":"FHS","description":null,"fullName":"Framingham Heart Study","count":128,
        "children":[],"category":"study_ids_dataset_ids","meta":null,
        "categoryRef":{"name":"study_ids_dataset_ids","display":"Study IDs/Dataset IDs","description":""},
        "parentRef":{"name":"study_ids_dataset_ids","display":"Study IDs/Dataset IDs","description":""}}],
        "search":"age","consents":["phs000007.c1"]}
        """;
    private static final Filter BOUND = new Filter(
        List.of(new Facet("phs000007", "FHS", null, "Framingham Heart Study", 128, List.of(), "study_ids_dataset_ids", null)), "age",
        List.of("phs000007.c1")
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConceptService conceptService;

    @MockitoBean
    private FacetService facetService;

    @Test
    void conceptSearchBindsTheFrontendFilter() throws Exception {
        when(conceptService.countConcepts(any(Filter.class))).thenReturn(0L);
        when(conceptService.listConcepts(any(Filter.class), any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(post("/concepts").contentType(MediaType.APPLICATION_JSON).content(FRONTEND_FILTER)).andExpect(status().isOk());

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        verify(conceptService).countConcepts(filter.capture());
        assertThat(filter.getValue()).isEqualTo(BOUND);
    }

    @Test
    void facetCountBindsTheFrontendFilter() throws Exception {
        when(facetService.getFacets(any(Filter.class))).thenReturn(List.of());

        mockMvc.perform(post("/facets").contentType(MediaType.APPLICATION_JSON).content(FRONTEND_FILTER)).andExpect(status().isOk());

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        verify(facetService).getFacets(filter.capture());
        assertThat(filter.getValue()).isEqualTo(BOUND);
    }

    @Test
    void strictMapperBindsTheFrontendFacetAndFilter() throws Exception {
        ObjectMapper strict = new ObjectMapper();

        Facet facet = strict.readValue(strict.readTree(FRONTEND_FILTER).path("facets").get(0).toString(), Facet.class);
        Filter filter = strict.readValue(FRONTEND_FILTER, Filter.class);

        assertThat(facet.name()).isEqualTo("phs000007");
        assertThat(facet.category()).isEqualTo("study_ids_dataset_ids");
        assertThat(filter).isEqualTo(BOUND);
        assertThat(filter.facets().getFirst().name()).isEqualTo("phs000007");
        assertThat(filter.facets().getFirst().category()).isEqualTo("study_ids_dataset_ids");
    }
}
