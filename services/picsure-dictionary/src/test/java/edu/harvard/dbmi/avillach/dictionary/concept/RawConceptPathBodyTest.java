package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the request the frontend sends to the three concept endpoints that take a concept path as their whole body: the path unquoted, under
 * {@code Content-Type: application/json}. The body is not JSON, and the handlers read it as a raw string, so the path reaches the service
 * character for character. A JSON-quoted path is not unquoted; the quotes and the doubled backslashes arrive as sent.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RawConceptPathBodyTest {

    private static final String DATASET = "phs000007";
    private static final String PATH = "\\demographics\\AGE\\";
    private static final Concept AGE =
        new ContinuousConcept(PATH, "AGE", "Age", DATASET, "Age of the participant", true, 18D, 89D, "FHS", Map.of());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConceptService conceptService;

    @Test
    void detailReadsTheUnquotedPathSentAsJson() throws Exception {
        when(conceptService.conceptDetail(DATASET, PATH)).thenReturn(Optional.of(AGE));

        mockMvc.perform(post("/concepts/detail/{dataset}", DATASET).contentType(MediaType.APPLICATION_JSON).content(PATH))
            .andExpect(status().isOk());

        verify(conceptService).conceptDetail(DATASET, PATH);
    }

    @Test
    void treeReadsTheUnquotedPathSentAsJson() throws Exception {
        when(conceptService.conceptTree(DATASET, PATH, 1)).thenReturn(Optional.of(AGE));

        mockMvc.perform(post("/concepts/tree/{dataset}?depth=1", DATASET).contentType(MediaType.APPLICATION_JSON).content(PATH))
            .andExpect(status().isOk());

        verify(conceptService).conceptTree(DATASET, PATH, 1);
    }

    @Test
    void hierarchyReadsTheUnquotedPathSentAsJson() throws Exception {
        when(conceptService.conceptHierarchy(DATASET, PATH)).thenReturn(List.of(AGE));

        mockMvc.perform(post("/concepts/hierarchy/{dataset}", DATASET).contentType(MediaType.APPLICATION_JSON).content(PATH))
            .andExpect(status().isOk());

        verify(conceptService).conceptHierarchy(DATASET, PATH);
    }

    @Test
    void aJsonQuotedPathIsNotUnquoted() throws Exception {
        String quoted = "\"\\\\demographics\\\\AGE\\\\\"";
        when(conceptService.conceptDetail(DATASET, quoted)).thenReturn(Optional.empty());

        mockMvc.perform(post("/concepts/detail/{dataset}", DATASET).contentType(MediaType.APPLICATION_JSON).content(quoted))
            .andExpect(status().isNotFound());

        verify(conceptService).conceptDetail(DATASET, quoted);
    }
}
