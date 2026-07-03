package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.data.entity.Query;
import edu.harvard.hms.dbmi.avillach.data.repository.QueryRepository;

/**
 * Full-context MockMvc test exercising the real {@code InternalTokenFilter} (registered as a plain {@code @Component} servlet filter,
 * applied to every request but a no-op outside {@code /internal/**}) together with {@link InternalQueryController}, same posture as
 * {@code NamedDatasetControllerTest}: no mocked security layer. {@code picsure.operations.internal-token} is set in
 * {@code src/test/resources/application.yml}, so the "unconfigured token fail-closed" behavior itself is covered by the dedicated pure-unit
 * {@code InternalTokenFilterTest} (constructing the filter directly with a blank token) rather than here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueryRepository queryRepo;

    @Value("${picsure.operations.internal-token}")
    private String validToken;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missingTokenIsForbiddenWithTheExactErrorBody() throws Exception {
        mockMvc.perform(get("/internal/queries/{id}", UUID.randomUUID())).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("FORBIDDEN")).andExpect(jsonPath("$.message").value("Forbidden"))
            .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void wrongTokenIsForbidden() throws Exception {
        mockMvc.perform(get("/internal/queries/{id}", UUID.randomUUID()).header(InternalTokenFilter.HEADER, "wrong-token"))
            .andExpect(status().isForbidden());
    }

    @Test
    void saveWithValidTokenReturns201WithPicsureId() throws Exception {
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\"}")
        ).andExpect(status().isCreated()).andExpect(jsonPath("$.picsureId").exists());
    }

    @Test
    void saveThenGetRoundTripsWithValidToken() throws Exception {
        String body = mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{\\\"q\\\":1}\",\"resourceResultId\":\"rr-1\",\"status\":\"QUEUED\",\"version\":\"7\"}")
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String picsureId = MAPPER.readTree(body).get("picsureId").asText();

        mockMvc.perform(get("/internal/queries/{picsureId}", picsureId).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.picsureId").value(picsureId))
            .andExpect(jsonPath("$.query").value("{\"q\":1}")).andExpect(jsonPath("$.resourceResultId").value("rr-1"))
            .andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.version").value("7"));
    }

    @Test
    void getUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/internal/queries/{id}", UUID.randomUUID()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchUpdatesStatusAndResultId() throws Exception {
        Query saved = queryRepo.save(new Query());

        mockMvc.perform(
            patch("/internal/queries/{picsureId}", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\",\"resourceResultId\":\"rr-9\"}")
        ).andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/internal/queries/{picsureId}", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.resourceResultId").value("rr-9"));
    }

    @Test
    void patchUnknownIdReturns404() throws Exception {
        mockMvc.perform(
            patch("/internal/queries/{id}", UUID.randomUUID()).header(InternalTokenFilter.HEADER, validToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}")
        ).andExpect(status().isNotFound());
    }

    @Test
    void dispatchReturnsQueryJsonWithResourceCredentialsStripped() throws Exception {
        Query saved = new Query();
        saved.setQuery("{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}");
        saved = queryRepo.save(saved);

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.queryJson").isString()).andExpect(
                result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).doesNotContain("secret")
                    .doesNotContain("resourceCredentials")
            );
    }

    @Test
    void dispatchUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/internal/queries/{id}/dispatch", UUID.randomUUID()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void dispatchWithoutTokenIsForbidden() throws Exception {
        Query saved = queryRepo.save(new Query());
        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid())).andExpect(status().isForbidden());
    }
}
