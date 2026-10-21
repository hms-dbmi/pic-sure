package edu.harvard.dbmi.avillach.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A deployment that sets the {@code picsure.openapi.examples} keys sees its own values in every example, parameter and curl command, with
 * concept paths escaped where they sit inside JSON. The properties here are read in {@code .properties} format, so each backslash of a
 * concept path is written twice.
 */
@SpringBootTest(
    properties = {"picsure.openapi.examples.dataset=phs000007", "picsure.openapi.examples.dataset-facet-category=study_ids_dataset_ids",
        "picsure.openapi.examples.categorical-concept-path=\\\\phs000007\\\\pht000009\\\\SEX\\\\",
        "picsure.openapi.examples.continuous-concept-path=\\\\phs000007\\\\pht000009\\\\AGE\\\\",
        "picsure.openapi.examples.search-term=blood", "picsure.openapi.examples.parent-concept-path=\\\\phs000007\\\\pht000009\\\\"}
)
@AutoConfigureMockMvc
class ConfiguredExamplesDocumentTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void configuredValuesReplaceTheAllInOneDefaults() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(body).path("paths");

        assertThat(body).doesNotContain("Synthea", "${picsure.openapi.examples");
        JsonNode searchOneDataset = paths.path("/concepts").path("post").path("requestBody").path("content").path("application/json")
            .path("examples").path("Search one dataset").path("value");
        assertThat(searchOneDataset.path("search").asText()).isEqualTo("blood");
        assertThat(searchOneDataset.path("facets").get(0).path("name").asText()).isEqualTo("phs000007");
        assertThat(searchOneDataset.path("facets").get(0).path("category").asText()).isEqualTo("study_ids_dataset_ids");
        JsonNode detail = paths.path("/concepts/detail/{dataset}").path("post");
        assertThat(detail.path("description").asText())
            .contains("concepts/detail/phs000007", "--data-binary '\\phs000007\\pht000009\\SEX\\'");
        assertThat(detail.path("parameters").get(0).path("example").asText()).isEqualTo("phs000007");
        assertThat(detail.path("requestBody").path("content").path("text/plain").path("schema").path("example").asText())
            .isEqualTo("\\phs000007\\pht000009\\SEX\\");
        JsonNode bulk = paths.path("/concepts/detail").path("post").path("requestBody").path("content").path("application/json")
            .path("schema").path("example");
        assertThat(bulk.get(0).asText()).isEqualTo("\\phs000007\\pht000009\\SEX\\");
        assertThat(bulk.get(1).asText()).isEqualTo("\\phs000007\\pht000009\\AGE\\");
    }
}
