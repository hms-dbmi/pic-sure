package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import static org.hamcrest.Matchers.isA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryFixtures;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

/**
 * Full-context MockMvc test: exercises the real {@link edu.harvard.hms.dbmi.avillach.operations.config.WebSecurityConfig} filter chain (not
 * a mocked security layer), so the "/dataset/** requires an authenticated caller" rule is proven end-to-end, same style as
 * {@code ConfigurationControllerTest}. Requests simulate the gateway by setting the {@code X-User-*} headers {@link GatewayUserResolver}
 * reads -- there is no login/session in this service. Email is the owner key throughout, never userId.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NamedDatasetControllerTest {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String QUERY_BODY = "{\"categoryFilters\":{}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamedDatasetRepository namedDatasetRepo;

    @Autowired
    private QueryRepository queryRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listWithoutIdentityIsForbidden() throws Exception {
        mockMvc.perform(get("/dataset/named")).andExpect(status().isForbidden());
    }

    @Test
    void listWithUserIdButNoEmailIsUnauthorized() throws Exception {
        // Passes the WebSecurityConfig gate (X-User-Id present) but trips the email guard in NamedDatasetService.
        mockMvc.perform(get("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorType").value("unauthorized"));
    }

    @Test
    void listReturnsOnlyCallersDatasets() throws Exception {
        Query aliceQuery = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        Query bobQuery = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("alice-1").setQuery(aliceQuery));
        namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bob-1").setQuery(bobQuery));

        mockMvc.perform(
            get("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name").value("alice-1"));
    }

    @Test
    void createPersistsUnderCallersEmailAndReturns201() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));

        mockMvc
            .perform(
                post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"my dataset\"}")
            ).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("my dataset")).andExpect(jsonPath("$.uuid").exists())
            .andExpect(jsonPath("$.user").value(ALICE)).andExpect(jsonPath("$.query.uuid").value(query.getUuid().toString()));
    }

    @Test
    void createWithUnknownQueryReturns404() throws Exception {
        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + UUID.randomUUID() + "\",\"name\":\"my dataset\"}")
        ).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"));
    }

    @Test
    void createSecondDatasetOverSameQueryAndUserReturns201AndBothAreListed() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));

        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"first\"}")
        ).andExpect(status().isCreated());

        mockMvc
            .perform(
                post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"second\"}")
            ).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("second"))
            .andExpect(jsonPath("$.query.uuid").value(query.getUuid().toString()));

        mockMvc
            .perform(
                get("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].query.uuid").value(query.getUuid().toString()))
            .andExpect(jsonPath("$[1].query.uuid").value(query.getUuid().toString()));
    }

    @Test
    void createWithMissingNameReturns400() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId());

        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void getOwnDatasetSucceeds() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("mine").setQuery(query));

        mockMvc.perform(
            get("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("mine"));
    }

    @Test
    void getAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            get("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"));
    }

    @Test
    void updateOwnDatasetSucceeds() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("old").setQuery(query));

        mockMvc.perform(
            put("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"new-name\",\"archived\":true}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("new-name")).andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    void updateAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            put("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"hijacked\"}")
        ).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"));
    }

    @Test
    void deleteOwnDatasetReturns204() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("mine").setQuery(query));

        mockMvc.perform(
            delete("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNoContent());
    }

    @Test
    void deleteAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            delete("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"));
    }

    /**
     * Pins the wire shape the frontend's {@code mapDataset()} reads: a top-level {@code user} plus a NESTED {@code query} object carrying
     * {@code uuid}, the decompressed {@code query} string, {@code startTime} as epoch MILLIS (a number -- it feeds {@code new Date(...)}
     * and a numeric sort), and {@code status}. Flattening this to a bare {@code queryId} makes {@code mapDataset()} throw on
     * {@code data.query.query}, which the Manage Datasets page renders as "API Error" even though the request returned 200.
     */
    @Test
    void listReturnsTheNestedQueryShapeTheFrontendMaps() throws Exception {
        Query query = QueryFixtures.withRandomId();
        query.setQuery(QUERY_BODY);
        query.setStartTime(java.time.Instant.ofEpochMilli(1690000000000L));
        query.setStatus(PicSureStatus.AVAILABLE);
        Query savedQuery = queryRepo.save(query);
        namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("alice-1").setQuery(savedQuery));

        mockMvc
            .perform(
                get("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andExpect(jsonPath("$[0].user").value(ALICE))
            .andExpect(jsonPath("$[0].query.uuid").value(savedQuery.getUuid().toString()))
            .andExpect(jsonPath("$[0].query.query").value(isA(String.class))).andExpect(jsonPath("$[0].query.startTime").isNumber())
            .andExpect(jsonPath("$[0].query.startTime").value(savedQuery.getStartTime().toEpochMilli()))
            .andExpect(jsonPath("$[0].query.status").value("AVAILABLE"));
    }

    @Test
    void queryBlobRoundTripsThroughTheLinkedNamedDataset() throws Exception {
        String emptyV3QueryString =
            "{\"select\":[],\"authorizationFilters\":[],\"phenotypicClause\":null,\"genomicFilters\":[],\"expectedResultType\":\"COUNT\",\"picsureId\":null,\"id\":null}";
        Query query = QueryFixtures.withRandomId();
        query.setQuery(emptyV3QueryString).setVersion("3");
        query = queryRepo.save(query);
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("with-query").setQuery(query));

        mockMvc
            .perform(
                get("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.query.uuid").value(query.getUuid().toString()))
            .andExpect(jsonPath("$.query.query").value("{\"query\":" + emptyV3QueryString + "}"));

        Query reloaded = queryRepo.findById(query.getUuid()).orElseThrow();
        assertThat(reloaded.getQuery()).isEqualTo(emptyV3QueryString);
    }

    @Test
    void queryConvertedFromV1ToV3() throws Exception {
        String emptyV1QueryString = "{\"categoryFilters\":{}}";
        String emptyV3QueryString =
            "{\"select\":[],\"authorizationFilters\":[],\"phenotypicClause\":null,\"genomicFilters\":[],\"expectedResultType\":\"COUNT\",\"picsureId\":null,\"id\":null}";
        Query query = QueryFixtures.withRandomId();
        query.setVersion("2");
        query.setQuery(emptyV1QueryString);
        query = queryRepo.save(query);
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("with-query").setQuery(query));

        mockMvc
            .perform(
                get("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.query.uuid").value(query.getUuid().toString()))
            .andExpect(jsonPath("$.query.query").value("{\"query\":" + emptyV3QueryString + "}"));
    }

    @Test
    void listAndDetailPreserveSavedLegacyFiltersWithoutUpdatingTheQueryRow() throws Exception {
        String stored = """
            {"query":{"categoryFilters":{"sex":["Female"]},"fields":["age"],"expectedResultType":"DATAFRAME"},
            "commonAreaUUID":"common-id"}
            """;
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(stored).setVersion("2"));
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("historical").setQuery(query));
        ObjectMapper json = new ObjectMapper();
        for (String path : List.of("/dataset/named", "/dataset/named/" + saved.getUuid())) {
            String body = mockMvc.perform(
                get(path).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice").header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode response = json.readTree(body);
            JsonNode dataset = response.isArray() ? response.get(0) : response;
            JsonNode request = json.readTree(dataset.at("/query/query").asText());
            assertThat(dataset.at("/query/uuid").asText()).isEqualTo(query.getUuid().toString());
            assertThat(request.at("/query/select/0").asText()).isEqualTo("age");
            assertThat(request.at("/query/phenotypicClause/conceptPath").asText()).isEqualTo("sex");
            assertThat(request.at("/query/phenotypicClause/values/0").asText()).isEqualTo("Female");
            assertThat(request.at("/query/expectedResultType").asText()).isEqualTo("DATAFRAME");
            assertThat(request.path("commonAreaUUID").asText()).isEqualTo("common-id");
        }
        Query reloaded = queryRepo.findById(query.getUuid()).orElseThrow();
        assertThat(json.readTree(reloaded.getQuery())).isEqualTo(json.readTree(stored));
        assertThat(reloaded.getVersion()).isEqualTo("2");
    }

    /** POST with {@code X-User-Id} but no {@code X-User-Email} passes the security chain and is rejected by the service email guard. */
    @Test
    void createWithUserIdButNoEmailIsUnauthorized() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));

        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"my dataset\"}")
        ).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorType").value("unauthorized"));
    }

    /** GET by id with {@code X-User-Id} but no {@code X-User-Email} is rejected by the service email guard. */
    @Test
    void getWithUserIdButNoEmailIsUnauthorized() throws Exception {
        mockMvc.perform(get("/dataset/named/{id}", UUID.randomUUID()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorType").value("unauthorized"));
    }

    /** PUT with {@code X-User-Id} but no {@code X-User-Email} is rejected by the service email guard. */
    @Test
    void updateWithUserIdButNoEmailIsUnauthorized() throws Exception {
        mockMvc.perform(
            put("/dataset/named/{id}", UUID.randomUUID()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .contentType(MediaType.APPLICATION_JSON).content("{\"queryId\":\"" + UUID.randomUUID() + "\",\"name\":\"renamed\"}")
        ).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorType").value("unauthorized"));
    }

    /** DELETE with {@code X-User-Id} but no {@code X-User-Email} is rejected by the service email guard. */
    @Test
    void deleteWithUserIdButNoEmailIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/dataset/named/{id}", UUID.randomUUID()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorType").value("unauthorized"));
    }

    /** PUT that re-points the caller's own dataset at a queryId with no stored query returns 404 from the query lookup. */
    @Test
    void updateRepointedToUnknownQueryReturns404() throws Exception {
        Query query = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("mine").setQuery(query));

        mockMvc.perform(
            put("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + UUID.randomUUID() + "\",\"name\":\"mine\"}")
        ).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorType").value("not_found"));
    }

    /** A PUT may re-point one of the caller's datasets at a query another of the caller's datasets already points at. */
    @Test
    void updateRepointedOntoAlreadyNamedQueryReturns200AndBothDatasetsPointAtIt() throws Exception {
        Query first = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        Query second = queryRepo.save(QueryFixtures.withRandomId().setQuery(QUERY_BODY));
        NamedDataset onFirst = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("first").setQuery(first));
        NamedDataset onSecond = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("second").setQuery(second));

        mockMvc.perform(
            put("/dataset/named/{id}", onSecond.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + first.getUuid() + "\",\"name\":\"second\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.query.uuid").value(first.getUuid().toString()));

        assertThat(namedDatasetRepo.findById(onFirst.getUuid()).orElseThrow().getQuery().getUuid()).isEqualTo(first.getUuid());
        assertThat(namedDatasetRepo.findById(onSecond.getUuid()).orElseThrow().getQuery().getUuid()).isEqualTo(first.getUuid());
    }

    private static final String ALICE_ID = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11";
    private static final String BOB_ID = "7c1b1f0e-2a3b-4c4d-8e5f-0a1b2c3d4e5f";
    private static final String FULL_DATAFRAME =
        "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME\"}}";

    private Query storedV3Query(String owner, String body) {
        Query query = QueryFixtures.withRandomId().setQuery(body).setVersion("3");
        query.setUserUuid(owner == null ? null : UUID.fromString(owner));
        return queryRepo.save(query);
    }

    private UUID generalQueryOf(String owner) {
        return QueryFixtures.storedGeneralQuery(jdbc, UUID.fromString(owner));
    }

    private Query storedVariant(String owner, UUID generalQuery, boolean sample, String resultType) {
        return queryRepo.save(QueryFixtures.variant(UUID.fromString(owner), generalQuery, sample, resultType));
    }

    private JsonNode createAsAlice(Query query) throws Exception {
        String body = mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, ALICE_ID).header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"cohort\"}")
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions putAsAlice(String datasetId, Query query, String name) throws Exception {
        return mockMvc.perform(
            put("/dataset/named/{id}", datasetId).header(GatewayUserResolver.HEADER_USER_ID, ALICE_ID)
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"" + name + "\"}")
        );
    }

    private JsonNode getAs(String userId, String path) throws Exception {
        String body = mockMvc
            .perform(get(path).header(GatewayUserResolver.HEADER_USER_ID, userId).header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode response = new ObjectMapper().readTree(body);
        return response.isArray() ? response.get(0) : response;
    }

    private static void assertVariant(JsonNode listed, Query expected) {
        assertThat(listed.fieldNames()).toIterable().containsExactly("queryId", "sample", "resultType");
        assertThat(listed.at("/queryId").asText()).isEqualTo(expected.getUuid().toString());
        assertThat(listed.at("/sample").isBoolean()).isTrue();
        assertThat(listed.at("/sample").asBoolean()).isEqualTo(expected.isSample());
        assertThat(listed.at("/resultType").textValue()).isEqualTo(expected.getResultType());
    }

    @Test
    void createSetsTheGeneralQueryAndReturnsItsVariants() throws Exception {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = storedVariant(ALICE_ID, general, false, "DATAFRAME");
        Query sample = storedVariant(ALICE_ID, general, true, "DATAFRAME");

        JsonNode created = createAsAlice(full);

        assertThat(created.at("/query/uuid").asText()).isEqualTo(full.getUuid().toString());
        assertThat(created.at("/queries")).hasSize(2);
        assertVariant(created.at("/queries/0"), full);
        assertVariant(created.at("/queries/1"), sample);
        NamedDataset stored = namedDatasetRepo.findById(UUID.fromString(created.at("/uuid").asText())).orElseThrow();
        assertThat(stored.getQuery().getUuid()).isEqualTo(full.getUuid());
        assertThat(stored.getQueryToVariantUuid()).isEqualTo(general);
    }

    @Test
    void getReturnsTheVariantsOfTheGeneralQuery() throws Exception {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = storedVariant(ALICE_ID, general, false, "DATAFRAME");
        String datasetId = createAsAlice(full).at("/uuid").asText();
        Query sample = storedVariant(ALICE_ID, general, true, "DATAFRAME");
        Query pfb = storedVariant(ALICE_ID, general, false, "DATAFRAME_PFB");
        Query untyped = storedVariant(ALICE_ID, general, false, null);
        storedVariant(ALICE_ID, generalQueryOf(ALICE_ID), false, "DATAFRAME");

        for (String path : List.of("/dataset/named/" + datasetId, "/dataset/named")) {
            JsonNode dataset = getAs(ALICE_ID, path);
            assertThat(dataset.at("/query/uuid").asText()).isEqualTo(full.getUuid().toString());
            assertThat(dataset.at("/queries")).hasSize(4);
            assertVariant(dataset.at("/queries/0"), untyped);
            assertVariant(dataset.at("/queries/1"), full);
            assertVariant(dataset.at("/queries/2"), pfb);
            assertVariant(dataset.at("/queries/3"), sample);
        }
        assertThat(namedDatasetRepo.findById(UUID.fromString(datasetId)).orElseThrow().getQuery().getUuid()).isEqualTo(full.getUuid());
    }

    @Test
    void getListsOnlyTheRowsOfTheCallingUser() throws Exception {
        UUID bobsGeneral = generalQueryOf(BOB_ID);
        Query bobsFull = storedVariant(BOB_ID, bobsGeneral, false, "DATAFRAME");
        storedVariant(BOB_ID, bobsGeneral, true, "DATAFRAME");
        NamedDataset shared = namedDatasetRepo
            .save(new NamedDataset().setUser(ALICE).setName("shared email").setQuery(bobsFull).setQueryToVariantUuid(bobsGeneral));

        for (String path : List.of("/dataset/named/" + shared.getUuid(), "/dataset/named")) {
            JsonNode asAlice = getAs(ALICE_ID, path);
            assertThat(asAlice.at("/query/uuid").asText()).isEqualTo(bobsFull.getUuid().toString());
            assertThat(asAlice.at("/queries").isArray()).isTrue();
            assertThat(asAlice.at("/queries")).isEmpty();
            assertThat(getAs(BOB_ID, path).at("/queries")).hasSize(2);
        }
    }

    @Test
    void getOfADatasetOnAnOwnerlessQueryListsNoQueries() throws Exception {
        Query ownerless = storedV3Query(null, FULL_DATAFRAME);
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("from the old stack").setQuery(ownerless));

        for (String path : List.of("/dataset/named/" + saved.getUuid(), "/dataset/named")) {
            JsonNode dataset = getAs(ALICE_ID, path);
            assertThat(dataset.at("/query/uuid").asText()).isEqualTo(ownerless.getUuid().toString());
            assertThat(dataset.at("/queries").isArray()).isTrue();
            assertThat(dataset.at("/queries")).isEmpty();
        }
    }

    @Test
    void getForACallerWhoseUserIdIsNotAUuidListsNoQueries() throws Exception {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = storedVariant(ALICE_ID, general, false, "DATAFRAME");
        NamedDataset saved =
            namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("cohort").setQuery(full).setQueryToVariantUuid(general));

        for (String path : List.of("/dataset/named/" + saved.getUuid(), "/dataset/named")) {
            JsonNode dataset = getAs("auth0|alice", path);
            assertThat(dataset.at("/query/uuid").asText()).isEqualTo(full.getUuid().toString());
            assertThat(dataset.at("/queries").isArray()).isTrue();
            assertThat(dataset.at("/queries")).isEmpty();
        }
    }

    @Test
    void createOnAnotherUsersQueryReturns403AndStoresNothing() throws Exception {
        Query bobs = storedVariant(BOB_ID, generalQueryOf(BOB_ID), false, "DATAFRAME");

        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, ALICE_ID).header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"queryId\":\"" + bobs.getUuid() + "\",\"name\":\"cohort\"}")
        ).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("forbidden"));

        assertThat(namedDatasetRepo.findByUser(ALICE)).isEmpty();
    }

    @Test
    void createOnAnOwnerlessQueryReturns201AndListsNoQueries() throws Exception {
        Query ownerless = storedV3Query(null, FULL_DATAFRAME);

        JsonNode created = createAsAlice(ownerless);

        assertThat(created.at("/query/uuid").asText()).isEqualTo(ownerless.getUuid().toString());
        assertThat(created.at("/queries").isArray()).isTrue();
        assertThat(created.at("/queries")).isEmpty();
        assertThat(namedDatasetRepo.findById(UUID.fromString(created.at("/uuid").asText())).orElseThrow().getQueryToVariantUuid()).isNull();
    }

    @Test
    void putRepointsAndReturnsTheNewQuery() throws Exception {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = storedVariant(ALICE_ID, general, false, "DATAFRAME");
        storedVariant(ALICE_ID, general, true, "DATAFRAME");
        UUID otherGeneral = generalQueryOf(ALICE_ID);
        Query count = storedVariant(ALICE_ID, otherGeneral, false, "COUNT");
        String datasetId = createAsAlice(full).at("/uuid").asText();

        putAsAlice(datasetId, count, "renamed").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("renamed"))
            .andExpect(jsonPath("$.uuid").value(datasetId)).andExpect(jsonPath("$.query.uuid").value(count.getUuid().toString()))
            .andExpect(jsonPath("$.queries", hasSize(1))).andExpect(jsonPath("$.queries[0].queryId").value(count.getUuid().toString()))
            .andExpect(jsonPath("$.queries[0].sample").value(false)).andExpect(jsonPath("$.queries[0].resultType").value("COUNT"));

        NamedDataset stored = namedDatasetRepo.findById(UUID.fromString(datasetId)).orElseThrow();
        assertThat(stored.getQuery().getUuid()).isEqualTo(count.getUuid());
        assertThat(stored.getQueryToVariantUuid()).isEqualTo(otherGeneral);
        JsonNode read = getAs(ALICE_ID, "/dataset/named/" + datasetId);
        assertThat(read.at("/query/uuid").asText()).isEqualTo(count.getUuid().toString());
        assertThat(read.at("/queries")).hasSize(1);
        assertVariant(read.at("/queries/0"), count);
    }

    @Test
    void putOntoAnotherUsersQueryReturns403AndChangesNothing() throws Exception {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = storedVariant(ALICE_ID, general, false, "DATAFRAME");
        Query bobs = storedVariant(BOB_ID, generalQueryOf(BOB_ID), false, "DATAFRAME");
        String datasetId = createAsAlice(full).at("/uuid").asText();

        putAsAlice(datasetId, bobs, "renamed").andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("forbidden"));

        NamedDataset stored = namedDatasetRepo.findById(UUID.fromString(datasetId)).orElseThrow();
        assertThat(stored.getQuery().getUuid()).isEqualTo(full.getUuid());
        assertThat(stored.getQueryToVariantUuid()).isEqualTo(general);
        assertThat(stored.getName()).isEqualTo("cohort");
    }

    @Test
    void putOntoAnOwnerlessQueryReturns200AndListsNoQueries() throws Exception {
        Query full = storedVariant(ALICE_ID, generalQueryOf(ALICE_ID), false, "DATAFRAME");
        Query ownerless = storedV3Query(null, FULL_DATAFRAME);
        String datasetId = createAsAlice(full).at("/uuid").asText();

        putAsAlice(datasetId, ownerless, "cohort").andExpect(status().isOk())
            .andExpect(jsonPath("$.query.uuid").value(ownerless.getUuid().toString())).andExpect(jsonPath("$.queries", hasSize(0)));

        NamedDataset stored = namedDatasetRepo.findById(UUID.fromString(datasetId)).orElseThrow();
        assertThat(stored.getQuery().getUuid()).isEqualTo(ownerless.getUuid());
        assertThat(stored.getQueryToVariantUuid()).isNull();
    }

    @Test
    void putWithTheSameQueryRenamesAndKeepsTheQueryAndItsVariants() throws Exception {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = storedVariant(ALICE_ID, general, false, "DATAFRAME");
        Query sample = storedVariant(ALICE_ID, general, true, "DATAFRAME");
        String datasetId = createAsAlice(full).at("/uuid").asText();

        putAsAlice(datasetId, full, "renamed").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("renamed"))
            .andExpect(jsonPath("$.query.uuid").value(full.getUuid().toString())).andExpect(jsonPath("$.queries", hasSize(2)))
            .andExpect(jsonPath("$.queries[0].queryId").value(full.getUuid().toString()))
            .andExpect(jsonPath("$.queries[1].queryId").value(sample.getUuid().toString()));

        NamedDataset stored = namedDatasetRepo.findById(UUID.fromString(datasetId)).orElseThrow();
        assertThat(stored.getQuery().getUuid()).isEqualTo(full.getUuid());
        assertThat(stored.getQueryToVariantUuid()).isEqualTo(general);
    }

    @Test
    void getReturnsTheStoredQueryWithoutConsentValues() throws Exception {
        String stored = """
            {"query":{"select":["\\\\demographics\\\\AGE\\\\"],
            "authorizationFilters":[{"conceptPath":"\\\\_consents\\\\","values":["phs000001.c1"]}],
            "phenotypicClause":{"phenotypicClauses":[
            {"phenotypicFilterType":"FILTER","conceptPath":"\\\\demographics\\\\SEX\\\\","values":["Male"]},
            {"phenotypicFilterType":"FILTER","conceptPath":"\\\\_topmed_consents\\\\","values":["phs000002.c2"]}],"operator":"AND"},
            "genomicFilters":[],"expectedResultType":"DATAFRAME"},"commonAreaUUID":"common-id"}
            """;
        Query query = storedV3Query(ALICE_ID, stored);
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("scoped").setQuery(query));
        ObjectMapper json = new ObjectMapper();

        for (String path : List.of("/dataset/named", "/dataset/named/" + saved.getUuid())) {
            String body = mockMvc.perform(
                get(path).header(GatewayUserResolver.HEADER_USER_ID, ALICE_ID).header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode response = json.readTree(body);
            JsonNode dataset = response.isArray() ? response.get(0) : response;
            JsonNode request = json.readTree(dataset.at("/query/query").asText());
            assertThat(body).doesNotContain("phs000001.c1", "phs000002.c2");
            assertThat(request.at("/query/authorizationFilters")).isEmpty();
            assertThat(request.at("/query/phenotypicClause/phenotypicClauses")).hasSize(1);
            assertThat(request.at("/query/phenotypicClause/phenotypicClauses/0/conceptPath").asText()).isEqualTo("\\demographics\\SEX\\");
            assertThat(request.path("commonAreaUUID").asText()).isEqualTo("common-id");
        }
        assertThat(json.readTree(queryRepo.findById(query.getUuid()).orElseThrow().getQuery())).isEqualTo(json.readTree(stored));
    }
}
