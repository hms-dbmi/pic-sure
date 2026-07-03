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

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.hms.dbmi.avillach.data.entity.Query;
import edu.harvard.hms.dbmi.avillach.data.repository.NamedDatasetRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.QueryRepository;

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamedDatasetRepository namedDatasetRepo;

    @Autowired
    private QueryRepository queryRepo;

    @Test
    void listWithoutIdentityIsForbidden() throws Exception {
        mockMvc.perform(get("/dataset/named/")).andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOnlyCallersDatasets() throws Exception {
        Query aliceQuery = queryRepo.save(new Query());
        Query bobQuery = queryRepo.save(new Query());
        namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("alice-1").setQuery(aliceQuery));
        namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bob-1").setQuery(bobQuery));

        mockMvc.perform(
            get("/dataset/named/").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name").value("alice-1"));
    }

    @Test
    void createPersistsUnderCallersEmailAndReturns201() throws Exception {
        Query query = queryRepo.save(new Query());

        mockMvc
            .perform(
                post("/dataset/named/").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                    .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"my dataset\"}")
            ).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("my dataset")).andExpect(jsonPath("$.uuid").exists())
            .andExpect(jsonPath("$.queryId").value(query.getUuid().toString()));
    }

    @Test
    void createWithUnknownQueryReturns404() throws Exception {
        mockMvc.perform(
            post("/dataset/named/").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + UUID.randomUUID() + "\",\"name\":\"my dataset\"}")
        ).andExpect(status().isNotFound());
    }

    @Test
    void createSecondDatasetOverSameQueryAndUserReturns409() throws Exception {
        Query query = queryRepo.save(new Query());

        mockMvc.perform(
            post("/dataset/named/").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"first\"}")
        ).andExpect(status().isCreated());

        mockMvc.perform(
            post("/dataset/named/").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"second\"}")
        ).andExpect(status().isConflict());
    }

    @Test
    void createWithMissingNameReturns400() throws Exception {
        Query query = queryRepo.save(new Query());

        mockMvc.perform(
            post("/dataset/named/").header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void getOwnDatasetSucceeds() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("mine").setQuery(query));

        mockMvc.perform(
            get("/dataset/named/{id}/", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("mine"));
    }

    @Test
    void getAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            get("/dataset/named/{id}/", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNotFound());
    }

    @Test
    void updateOwnDatasetSucceeds() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("old").setQuery(query));

        mockMvc.perform(
            put("/dataset/named/{id}/", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"new-name\",\"archived\":true}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("new-name")).andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    void updateAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            put("/dataset/named/{id}/", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryId\":\"" + query.getUuid() + "\",\"name\":\"hijacked\"}")
        ).andExpect(status().isNotFound());
    }

    @Test
    void deleteOwnDatasetReturns204() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("mine").setQuery(query));

        mockMvc.perform(
            delete("/dataset/named/{id}/", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNoContent());
    }

    @Test
    void deleteAnotherUsersDatasetReturns404() throws Exception {
        Query query = queryRepo.save(new Query());
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(BOB).setName("bobs").setQuery(query));

        mockMvc.perform(
            delete("/dataset/named/{id}/", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isNotFound());
    }

    @Test
    void queryBlobRoundTripsThroughTheLinkedNamedDataset() throws Exception {
        Query query = new Query();
        query.setQuery("{\"consentGroups\":[\"phs000001\"]}");
        query = queryRepo.save(query);
        NamedDataset saved = namedDatasetRepo.save(new NamedDataset().setUser(ALICE).setName("with-query").setQuery(query));

        mockMvc.perform(
            get("/dataset/named/{id}/", saved.getUuid()).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice")
                .header(GatewayUserResolver.HEADER_USER_EMAIL, ALICE)
        ).andExpect(status().isOk()).andExpect(jsonPath("$.queryId").value(query.getUuid().toString()));

        Query reloaded = queryRepo.findById(query.getUuid()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getQuery()).isEqualTo("{\"consentGroups\":[\"phs000001\"]}");
    }
}
