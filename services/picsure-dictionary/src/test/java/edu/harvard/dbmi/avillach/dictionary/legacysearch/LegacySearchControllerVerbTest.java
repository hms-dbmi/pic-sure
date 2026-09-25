package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Results;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests of which HTTP verbs {@code /search} answers. The Bruno suite and the R and Python adapters send the legacy
 * search as a POST with a JSON body.
 */
class LegacySearchControllerVerbTest {

    private static final String BODY = "{\"query\":{\"searchTerm\":\"age\",\"limit\":10}}";

    private LegacySearchService legacySearchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        legacySearchService = mock(LegacySearchService.class);
        when(legacySearchService.getSearchResults(any(), any())).thenReturn(new Results(List.of()));
        LegacySearchController controller = new LegacySearchController(legacySearchService, new LegacySearchQueryMapper());
        ReflectionTestUtils.setField(controller, "httpRequest", new MockHttpServletRequest());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void postRunsTheSearch() throws Exception {
        mockMvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
    }

    /** Every other verb is answered 405 with an {@code Allow} header naming POST, and never runs the search. */
    @Test
    void otherVerbsAnswerMethodNotAllowed() throws Exception {
        List<MockHttpServletRequestBuilder> others = List.of(get("/search"), put("/search"), patch("/search"), delete("/search"));
        for (MockHttpServletRequestBuilder other : others) {
            mockMvc.perform(other.contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        }
        verifyNoInteractions(legacySearchService);
    }
}
