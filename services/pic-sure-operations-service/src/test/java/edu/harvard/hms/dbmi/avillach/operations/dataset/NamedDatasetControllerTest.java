package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
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
    private static final String QUERY_BODY = "{\"query\":{\"categoryFilters\":{}}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamedDatasetRepository namedDatasetRepo;

    @Autowired
    private QueryRepository queryRepo;

    @Test
    void listWithoutIdentityIsForbidden() throws Exception {
        mockMvc.perform(get("/dataset/named")).andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOnlyCallersDatasets() throws Exception {
        Query aliceQuery = queryRepo.save(new Query());
        Query bobQuery = queryRepo.save(new Query());
        namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("alice-1").setQuery(aliceQuery));
        namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bob-1").setQuery(bobQuery));

        mockMvc.perform(
            get("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name").value("alice-1"));
    }

    @Test
    void createPersistsUnderCallersEmailAndReturns201() throws Exception {
        Query query = queryRepo.save(new Query());

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
        ).andExpect(status().isNotFound());
    }

    @Test
    void createSecondDatasetOverSameQueryAndUserReturns409() throws Exception {
        Query query = queryRepo.save(new Query());

        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"first\"}")
        ).andExpect(status().isCreated());

        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"second\"}")
        ).andExpect(status().isConflict());
    }

    @Test
    void createWithMissingNameReturns400() throws Exception {
        Query query = queryRepo.save(new Query());

        mockMvc.perform(
            post("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void getOwnDatasetSucceeds() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("mine").setQuery(query));

        mockMvc.perform(
            get("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("mine"));
    }

    @Test
    void getAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            get("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNotFound());
    }

    @Test
    void updateOwnDatasetSucceeds() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("old").setQuery(query));

        mockMvc.perform(
            put("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"new-name\",\"archived\":true}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("new-name")).andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    void updateAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            put("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"hijacked\"}")
        ).andExpect(status().isNotFound());
    }

    @Test
    void deleteOwnDatasetReturns204() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("mine").setQuery(query));

        mockMvc.perform(
            delete("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNoContent());
    }

    @Test
    void deleteAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            delete("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNotFound());
    }

    /**
     * Pins the wire shape the frontend's {@code mapDataset()} reads: a top-level {@code user} plus a NESTED {@code query} object carrying
     * {@code uuid}, the decompressed {@code query} string, {@code startTime} as epoch MILLIS (a number -- it feeds {@code new Date(...)}
     * and a numeric sort), and {@code status}. Flattening this to a bare {@code queryId} makes {@code mapDataset()} throw on
     * {@code data.query.query}, which the Manage Datasets page renders as "API Error" even though the request returned 200.
     */
    @Test
    void listReturnsTheNestedQueryShapeTheFrontendMaps() throws Exception {
        Query query = new Query();
        query.setQuery(QUERY_BODY);
        query.setStartTime(new java.sql.Date(1690000000000L));
        query.setStatus(PicSureStatus.AVAILABLE);
        Query savedQuery = queryRepo.save(query);
        namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("alice-1").setQuery(savedQuery));

        mockMvc
            .perform(
                get("/dataset/named").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andExpect(jsonPath("$[0].user").value(ALICE))
            .andExpect(jsonPath("$[0].query.uuid").value(savedQuery.getUuid().toString()))
            .andExpect(jsonPath("$[0].query.query").value(QUERY_BODY)).andExpect(jsonPath("$[0].query.startTime").isNumber())
            .andExpect(jsonPath("$[0].query.startTime").value(savedQuery.getStartTime().getTime()))
            .andExpect(jsonPath("$[0].query.status").value("AVAILABLE"));
    }

    @Test
    void queryBlobRoundTripsThroughTheLinkedNamedDataset() throws Exception {
        Query query = new Query();
        query.setQuery("{\"consentGroups\":[\"phs000001\"]}");
        query = queryRepo.save(query);
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("with-query").setQuery(query));

        mockMvc
            .perform(
                get("/dataset/named/{id}", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.query.uuid").value(query.getUuid().toString()))
            .andExpect(jsonPath("$.query.query").value("{\"consentGroups\":[\"phs000001\"]}"));

        Query reloaded = queryRepo.findById(query.getUuid()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getQuery()).isEqualTo("{\"consentGroups\":[\"phs000001\"]}");
    }
}
