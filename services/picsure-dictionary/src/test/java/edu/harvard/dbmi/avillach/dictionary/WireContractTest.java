package edu.harvard.dbmi.avillach.dictionary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptService;
import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.LegacySearchService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pins the JSON the dictionary actually accepts and emits, as opposed to the Java signatures the other controller tests exercise. Two
 * things only a real bind can show: that an unknown property is now a 400 rather than a silently dropped field, and that a page is
 * serialized as the shared PaginatedResponse rather than a Spring PageImpl.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WireContractTest {

    private static final Concept CONCEPT =
        new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a"), true, "", List.of(), Map.of());

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ConceptService conceptService;

    @MockitoBean
    LegacySearchService legacySearchService;

    @Test
    void conceptDetailBindsAConceptPathRequest() throws Exception {
        Mockito.when(conceptService.conceptDetail("my_dataset", "/foo//bar")).thenReturn(Optional.of(CONCEPT));

        mockMvc
            .perform(post("/concepts/detail/my_dataset").contentType(MediaType.APPLICATION_JSON).content("{\"conceptPath\":\"/foo//bar\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.conceptPath").value("/foo//bar"));
    }

    @Test
    void conceptDetailRejectsTheOldBareStringBody() throws Exception {
        mockMvc.perform(post("/concepts/detail/my_dataset").contentType(MediaType.APPLICATION_JSON).content("\"/foo//bar\""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void conceptDetailRejectsAnUnknownProperty() throws Exception {
        mockMvc.perform(
            post("/concepts/detail/my_dataset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conceptPath\":\"/foo//bar\",\"concept_path\":\"/typo\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void conceptTreeAndHierarchyBindTheSameRequest() throws Exception {
        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", 2)).thenReturn(Optional.of(CONCEPT));
        Mockito.when(conceptService.conceptHierarchy("my_dataset", "/foo")).thenReturn(List.of(CONCEPT));

        mockMvc.perform(post("/concepts/tree/my_dataset").contentType(MediaType.APPLICATION_JSON).content("{\"conceptPath\":\"/foo\"}"))
            .andExpect(status().isOk());
        mockMvc
            .perform(post("/concepts/hierarchy/my_dataset").contentType(MediaType.APPLICATION_JSON).content("{\"conceptPath\":\"/foo\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void listConceptsEmitsThePaginatedResponseShape() throws Exception {
        Mockito.when(conceptService.listConcepts(Mockito.any(), Mockito.any())).thenReturn(List.of(CONCEPT));
        Mockito.when(conceptService.countConcepts(Mockito.any())).thenReturn(37L);

        mockMvc
            .perform(
                post("/concepts?page_number=2&page_size=10").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"facets\":[],\"search\":\"foo\",\"consents\":[]}")
            ).andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(1)).andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.total").value(37))
            // The Spring PageImpl envelope is gone
            .andExpect(jsonPath("$.content").doesNotExist()).andExpect(jsonPath("$.totalElements").doesNotExist())
            .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void dumpConceptsEmitsThePaginatedResponseShape() throws Exception {
        Mockito.when(conceptService.listDetailedConcepts(Mockito.any(), Mockito.any())).thenReturn(List.of(CONCEPT));
        Mockito.when(conceptService.countConcepts(Mockito.any())).thenReturn(1L);

        mockMvc.perform(get("/concepts/dump?page_number=0&page_size=10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(1)).andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void searchBindsTheSharedSearchRequest() throws Exception {
        Mockito.when(legacySearchService.getSearchResults(Mockito.eq("age"), Mockito.any())).thenReturn(List.of(CONCEPT));

        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"age\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(1)).andExpect(jsonPath("$.results[0].conceptPath").value("/foo//bar"));
    }

    @Test
    void searchRejectsTheLegacyEnvelope() throws Exception {
        // {"query":{"searchTerm":...,"limit":...}} -- query is a string now, and the envelope's siblings are unknown properties
        mockMvc.perform(
            post("/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"@type\":\"GeneralQueryRequest\",\"query\":{\"searchTerm\":\"age\",\"limit\":100}}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void infoTakesNoBodyAndEmitsResourceInfo() throws Exception {
        mockMvc.perform(post("/info")).andExpect(status().isOk()).andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.name").value(":)")).andExpect(jsonPath("$.queryFormats.length()").value(0));
    }
}
