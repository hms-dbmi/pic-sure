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

    @Value("${picsure.operations.internal-token}")
    private String validToken;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The canonical bare v3 body a row written since Task 15 carries. */
    private static final String BARE_V3_QUERY = "{\"select\":[\"\\\\age\\\\\"],\"expectedResultType\":\"COUNT\"}";

    /** The same query as it was stored BEFORE Task 15: the legacy QueryRequest envelope, credentials and all. */
    private static final String LEGACY_ENVELOPE_ROW = "{\"@type\":\"GeneralQueryRequest\","
        + "\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"}," + "\"query\":" + BARE_V3_QUERY + ",\"resourceUUID\":null}";

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

    /**
     * {@code status} binds as the {@code PicSureStatus} enum now that the body is the shared contract record, so an unknown NAME is a
     * binding failure. That must stay a caller error (400), not a 500 -- the ordinal is never accepted on the wire either way.
     */
    @Test
    void saveWithAnUnknownStatusNameReturns400() throws Exception {
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{\\\"q\\\":1}\",\"status\":\"NOT_A_REAL_STATUS\"}")
        ).andExpect(status().isBadRequest());
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

    /**
     * A row written since Task 15 stores the BARE v3 query; dispatch hands back exactly that, as a JSON STRING (never a nested object --
     * the gateway's {@code QueryAuthFetcher} parses {@code queryJson} as a string).
     */
    @Test
    void dispatchOfABareRowReturnsTheBareQueryAsAString() throws Exception {
        Query saved = new Query();
        saved.setQuery(BARE_V3_QUERY);
        saved = queryRepo.save(saved);

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.queryJson").isString())
            .andExpect(jsonPath("$.queryJson").value(BARE_V3_QUERY));
    }

    /**
     * A row written BEFORE Task 15 stores the legacy {@code QueryRequest} envelope. Dispatch unwraps it (and strips the credentials it
     * carries) so the gateway sees the SAME node shape as for a new row -- the authorization rules must not depend on a row's age.
     */
    @Test
    void dispatchOfALegacyEnvelopeRowReturnsTheSameBareQueryWithCredentialsStripped() throws Exception {
        Query saved = new Query();
        saved.setQuery(LEGACY_ENVELOPE_ROW);
        saved = queryRepo.save(saved);

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.queryJson").isString())
            .andExpect(jsonPath("$.queryJson").value(BARE_V3_QUERY)).andExpect(
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
}
