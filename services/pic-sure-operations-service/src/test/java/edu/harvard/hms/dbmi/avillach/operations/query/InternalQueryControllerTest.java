package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * Full-context MockMvc test exercising the real {@code InternalTokenFilter} (registered by {@code InternalTokenFilterConfig} as a
 * {@code FilterRegistrationBean} scoped to the {@code /internal/*} URL pattern) together with {@link InternalQueryController}, same posture
 * as {@code NamedDatasetControllerTest}: no mocked security layer. {@code picsure.operations.internal-token} is set in
 * {@code src/test/resources/application.yml}, so the "unconfigured token fail-closed" behavior itself is covered by the dedicated pure-unit
 * {@code InternalTokenFilterTest} (constructing the filter directly with a blank token) rather than here. The context-path-robustness of
 * the URL-pattern-based registration itself is covered separately by {@code InternalTokenFilterContextPathTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueryRepository queryRepo;

    @Autowired
    private QueryRunRepository runRepo;

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
    void saveWithMalformedBase64MetadataReturns400() throws Exception {
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\",\"metadata\":\"not-valid-base64!!!\"}")
        ).andExpect(status().isBadRequest());
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
    void saveWithIdOwnerConsentsAndTriggerRoundTripsThroughGet() throws Exception {
        String picsureId = "8694e3d4-5cb4-410f-8431-993445e6d3f6";
        String owner = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";

        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content(ownedSave(picsureId, owner, "QUEUED", "[\"phs2.c1\",\"phs1.c1\"]", "FIRST_SUBMIT"))
        ).andExpect(status().isCreated()).andExpect(jsonPath("$.picsureId").value(picsureId));

        String runId = runRepo.findByQueryUuidOrderBySubmittedAtAsc(UUID.fromString(picsureId)).getFirst().getRunId().toString();
        JsonNode stored = storedRow(picsureId);
        assertThat(stored.get("picsureId").asText()).isEqualTo(picsureId);
        assertThat(stored.get("userUuid").asText()).isEqualTo(owner);
        assertThat(stored.get("consents")).isEqualTo(MAPPER.readTree("{\"\\\\_consents\\\\\":[\"phs1.c1\",\"phs2.c1\"]}"));
        assertThat(stored.get("runId").asText()).isEqualTo(runId);
    }

    @Test
    void secondSaveOfTheSameIdAnswers201WithTheSameIdAndKeepsOneRow() throws Exception {
        String picsureId = "8694e3d4-5cb4-410f-8431-993445e6d3f6";
        String owner = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content(ownedSave(picsureId, owner, "QUEUED", "[\"phs1.c1\",\"phs2.c1\"]", "FIRST_SUBMIT"))
        ).andExpect(status().isCreated());

        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content(ownedSave(picsureId, owner, "PENDING", "[\"phs1.c1\"]", "CONSENT_CHANGE"))
        ).andExpect(status().isCreated()).andExpect(jsonPath("$.picsureId").value(picsureId));

        assertThat(queryRepo.count()).isEqualTo(1);
        assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(UUID.fromString(picsureId))).hasSize(2);
        JsonNode stored = storedRow(picsureId);
        assertThat(stored.get("status").asText()).isEqualTo("PENDING");
        assertThat(stored.get("consents")).isEqualTo(MAPPER.readTree("{\"\\\\_consents\\\\\":[\"phs1.c1\"]}"));
    }

    @Test
    void saveWithUnknownTriggerReturns400() throws Exception {
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\",\"trigger\":\"NOT_A_TRIGGER\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void patchNamingAnOlderRunLeavesTheStatusAndPatchNamingTheLatestRunWritesIt() throws Exception {
        String picsureId = "8694e3d4-5cb4-410f-8431-993445e6d3f6";
        String owner = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content(ownedSave(picsureId, owner, "QUEUED", "[\"phs1.c1\",\"phs2.c1\"]", "FIRST_SUBMIT"))
        ).andExpect(status().isCreated());
        String olderRun = storedRunId(picsureId);
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content(ownedSave(picsureId, owner, "PENDING", "[\"phs1.c1\"]", "CONSENT_CHANGE"))
        ).andExpect(status().isCreated());
        String latestRun = storedRunId(picsureId);

        mockMvc.perform(
            patch("/internal/queries/{picsureId}", picsureId).header(InternalTokenFilter.HEADER, validToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\",\"runId\":\"" + olderRun + "\"}")
        ).andExpect(status().isNoContent());
        mockMvc.perform(get("/internal/queries/{picsureId}", picsureId).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.runId").value(latestRun));

        mockMvc.perform(
            patch("/internal/queries/{picsureId}", picsureId).header(InternalTokenFilter.HEADER, validToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\",\"runId\":\"" + latestRun + "\"}")
        ).andExpect(status().isNoContent());
        mockMvc.perform(get("/internal/queries/{picsureId}", picsureId).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void dispatchOfAnOwnedRowCarriesNoOwnerConsentsOrRun() throws Exception {
        String picsureId = "8694e3d4-5cb4-410f-8431-993445e6d3f6";
        String owner = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content(ownedSave(picsureId, owner, "QUEUED", "[\"phs1.c1\"]", "FIRST_SUBMIT"))
        ).andExpect(status().isCreated());

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", picsureId).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(
                result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("phs1.c1").doesNotContain("consents")
                    .doesNotContain(owner).doesNotContain("runId")
            );
    }

    @Test
    void getUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/internal/queries/{id}", UUID.randomUUID()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchUpdatesStatusAndResultId() throws Exception {
        Query saved = queryRepo.save(QueryFixtures.withRandomId());

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
    void getStripsResourceCredentialsLikeDispatchDoes() throws Exception {
        // the full GET must not be a credential side door around dispatch's stripping
        Query saved = QueryFixtures.withRandomId();
        saved.setQuery("{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}");
        saved = queryRepo.save(saved);

        mockMvc.perform(get("/internal/queries/{picsureId}", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(
                result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).doesNotContain("secret")
                    .doesNotContain("resourceCredentials")
            );
    }

    @Test
    void dispatchReturnsQueryJsonWithResourceCredentialsStripped() throws Exception {
        Query saved = QueryFixtures.withRandomId();
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
        Query saved = queryRepo.save(QueryFixtures.withRandomId());
        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid())).andExpect(status().isForbidden());
    }

    // --- the federated/GIC surface is gone; these endpoints must stay gone ---
    // A 404 here is the contract: it guards against someone reintroducing the routes while wiring something
    // unrelated.

    @Test
    void byCommonAreaIsGone() throws Exception {
        mockMvc.perform(get("/internal/queries/by-common-area/{id}", UUID.randomUUID()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void sitesByDomainIsGone() throws Exception {
        mockMvc.perform(get("/internal/sites/by-domain/{domain}", "harvard.edu").header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isNotFound());
    }

    private static String ownedSave(String picsureId, String owner, String status, String consentValues, String trigger) {
        return "{\"query\":\"{\\\"q\\\":1}\",\"resourceResultId\":\"" + picsureId + "\",\"status\":\"" + status
            + "\",\"version\":\"3\",\"picsureId\":\"" + picsureId + "\",\"userUuid\":\"" + owner + "\",\"consents\":{\"\\\\_consents\\\\\":"
            + consentValues + "},\"trigger\":\"" + trigger + "\"}";
    }

    private String storedRunId(String picsureId) throws Exception {
        return storedRow(picsureId).get("runId").asText();
    }

    private JsonNode storedRow(String picsureId) throws Exception {
        return MAPPER.readTree(
            mockMvc.perform(get("/internal/queries/{picsureId}", picsureId).header(InternalTokenFilter.HEADER, validToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
        );
    }
}
