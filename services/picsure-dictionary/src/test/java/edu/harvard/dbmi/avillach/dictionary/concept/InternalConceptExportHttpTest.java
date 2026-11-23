package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;

/**
 * Full-context coverage of {@code POST /internal/concepts/export-metadata} over the seeded PostgreSQL container, with the shared token
 * filter registered and a no-show list that hides {@code drs_uri}. The right token returns the raw {@code drs_uri} value, a missing or
 * wrong token is answered 403 before any query, and the public {@code POST /concepts/detail} stays open and keeps hiding {@code drs_uri}
 * for the same concept while agreeing on display, dataset and description.
 */
@Testcontainers
@SpringBootTest(
    properties = {"picsure.internal.token=" + InternalConceptExportHttpTest.TOKEN, "metadata.no_show_list=values,stigmatized,drs_uri"}
)
@AutoConfigureMockMvc
class InternalConceptExportHttpTest {

    static final String TOKEN = "inbound-secret";

    private static final String EXPORT_URL = "/internal/concepts/export-metadata";
    private static final String COLA_PATH = "\\phs000007\\pht000021\\phv00003844\\FL200\\";
    private static final int DRS_URI_ROW_ID = 900_001;
    private static final String DRS_URI_JSON = "[\"drs://dg.4503:dg.4503/aaa\", \"drs://dg.4503:dg.4503/bbb\"]";
    private static final String EXPORT_BODY =
        "{\"conceptPaths\":[\"\\\\phs000007\\\\pht000021\\\\phv00003844\\\\FL200\\\\\"],\"metaKeys\":[\"drs_uri\"]}";
    private static final String DETAIL_BODY = "[\"\\\\phs000007\\\\pht000021\\\\phv00003844\\\\FL200\\\\\"]";

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void addDrsUriRow() {
        jdbcTemplate.update(
            "INSERT INTO concept_node_meta (concept_node_meta_id, concept_node_id, key, value) OVERRIDING SYSTEM VALUE "
                + "SELECT ?, concept_node_id, 'drs_uri', ? FROM concept_node WHERE concept_path = ?",
            DRS_URI_ROW_ID, DRS_URI_JSON, COLA_PATH
        );
    }

    @AfterEach
    void removeDrsUriRow() {
        jdbcTemplate.update("DELETE FROM concept_node_meta WHERE concept_node_meta_id = ?", DRS_URI_ROW_ID);
    }

    private JsonNode publicDetail() throws Exception {
        String body = mockMvc.perform(post("/concepts/detail").contentType(MediaType.APPLICATION_JSON).content(DETAIL_BODY))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode concepts = objectMapper.readTree(body);
        assertThat(concepts).hasSize(1);
        return concepts.get(0);
    }

    @Test
    void correctTokenReturnsTheRawDrsUriTheNoShowListHides() throws Exception {
        mockMvc
            .perform(
                post(EXPORT_URL).header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON).content(EXPORT_BODY)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].conceptPath").value(COLA_PATH))
            .andExpect(jsonPath("$[0].meta.drs_uri").value(DRS_URI_JSON)).andExpect(jsonPath("$[0].meta.length()").value(1));
    }

    @Test
    void missingTokenIsForbidden() throws Exception {
        mockMvc.perform(post(EXPORT_URL).contentType(MediaType.APPLICATION_JSON).content(EXPORT_BODY)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("FORBIDDEN")).andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void wrongTokenIsForbidden() throws Exception {
        mockMvc.perform(
            post(EXPORT_URL).header(InternalTokenFilter.HEADER, "not-the-secret").contentType(MediaType.APPLICATION_JSON)
                .content(EXPORT_BODY)
        ).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("FORBIDDEN"));
    }

    @Test
    void publicConceptDetailNeedsNoTokenAndStillHidesDrsUri() throws Exception {
        JsonNode concept = publicDetail();

        List<String> metaKeys = new ArrayList<>();
        concept.path("meta").fieldNames().forEachRemaining(metaKeys::add);
        assertThat(metaKeys).isNotEmpty().doesNotContain("drs_uri", "Drs Uri", "Stigmatized");
        assertThat(concept.toString()).doesNotContain("drs://");
    }

    @Test
    void exportAgreesWithPublicConceptDetailOnDisplayDatasetAndDescription() throws Exception {
        JsonNode detail = publicDetail();

        String body = mockMvc.perform(
            post(EXPORT_URL).header(InternalTokenFilter.HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON).content(EXPORT_BODY)
        ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode exported = objectMapper.readTree(body).get(0);

        assertThat(exported.path("dataset").asText()).isEqualTo("phs000007");
        for (String field : List.of("conceptPath", "display", "dataset", "description")) {
            assertThat(exported.path(field)).as(field).isEqualTo(detail.path(field));
            assertThat(exported.path(field).asText()).as(field).isNotBlank();
        }
    }
}
