package edu.harvard.hms.dbmi.avillach.query;

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
 * A deployment that sets the {@code picsure.openapi.examples} keys sees its own concept paths, value and gene in every request example. The
 * properties here are read in {@code .properties} format, so each backslash of a concept path is written twice.
 */
@SpringBootTest(
    properties = {"picsure.openapi.examples.categorical-concept-path=\\\\phs000007\\\\pht000009\\\\SEX\\\\",
        "picsure.openapi.examples.categorical-value=F",
        "picsure.openapi.examples.second-categorical-concept-path=\\\\phs000007\\\\RACE\\\\",
        "picsure.openapi.examples.continuous-concept-path=\\\\phs000007\\\\pht000009\\\\AGE\\\\", "picsure.openapi.examples.gene=CHD8"}
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
        JsonNode sync = paths.path("/hpds/{backend}/query/sync").path("post").path("requestBody").path("content").path("application/json")
            .path("examples");
        JsonNode count = sync.path("COUNT").path("value").path("query").path("phenotypicClause");
        assertThat(count.path("conceptPath").asText()).isEqualTo("\\phs000007\\pht000009\\SEX\\");
        assertThat(count.path("values").get(0).asText()).isEqualTo("F");
        assertThat(
            sync.path("VARIANT_COUNT_FOR_QUERY").path("value").path("query").path("genomicFilters").get(0).path("values").get(0).asText()
        ).isEqualTo("CHD8");
        JsonNode dataframe = paths.path("/hpds/{backend}/query").path("post").path("requestBody").path("content").path("application/json")
            .path("examples").path("DATAFRAME").path("value").path("query").path("select");
        assertThat(dataframe.get(1).asText()).isEqualTo("\\phs000007\\pht000009\\AGE\\");
    }
}
