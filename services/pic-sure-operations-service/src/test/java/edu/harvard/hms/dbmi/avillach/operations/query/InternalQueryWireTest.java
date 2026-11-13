package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.dbmi.avillach.domain.DispatchResponse;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;

/**
 * Holds the two internal query producers to the exact bytes they emit and to the shared records they declare. The three wire tests assert
 * whole response bodies, so they pass against a handler that builds a map and against one that returns a record only when both write the
 * same JSON. The signature test is what requires the records.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalQueryWireTest {

    private static final Pattern SAVED_BODY =
        Pattern.compile("^\\{\"picsureId\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"}$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueryRepository queryRepo;

    @Autowired
    private QueryToVariantRepository generalQueryRepo;

    @Value("${picsure.operations.internal-token}")
    private String validToken;

    @Test
    void saveAnswers201WithOnePicsureIdMember() throws Exception {
        String body = mockMvc
            .perform(
                post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\"}")
            ).andExpect(status().isCreated()).andExpect(content().contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse()
            .getContentAsString();

        Matcher saved = SAVED_BODY.matcher(body);
        assertThat(saved.matches()).as("save body %s", body).isTrue();
        assertThat(queryRepo.findById(UUID.fromString(saved.group(1)))).isPresent();
    }

    @Test
    void saveWithAPicsureIdAnswers201WithThatId() throws Exception {
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON).content(
                "{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\",\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\","
                    + "\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\",\"consents\":{\"\\\\_consents\\\\\":[\"phs1.c1\"]},"
                    + "\"trigger\":\"FIRST_SUBMIT\",\"queryToVariantUuid\":\"3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f\"}"
            )
        ).andExpect(status().isCreated()).andExpect(content().string("{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"}"));
    }

    @Test
    void saveCarriesTheGeneralQuerySampleAndResultTypeOntoTheRow() throws Exception {
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON).content(
                "{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\",\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\","
                    + "\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\",\"consents\":{\"\\\\_consents\\\\\":[\"phs1.c1\"]},"
                    + "\"trigger\":\"FIRST_SUBMIT\",\"queryToVariantUuid\":\"3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f\",\"sample\":true,"
                    + "\"resultType\":\"DATAFRAME_PFB\"}"
            )
        ).andExpect(status().isCreated()).andExpect(content().string("{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"}"));

        Query row = queryRepo.findById(UUID.fromString("8694e3d4-5cb4-410f-8431-993445e6d3f6")).orElseThrow();
        assertThat(row.getQueryToVariantUuid()).isEqualTo(UUID.fromString("3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f"));
        assertThat(row.isSample()).isTrue();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME_PFB");
        assertThat(generalQueryRepo.findById(UUID.fromString("3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f")).orElseThrow().getUserUuid())
            .isEqualTo(UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11"));
    }

    @Test
    void payloadWithoutTheVariantMembersStillBindsAndStoresARowUnderNoGeneralQuery() throws Exception {
        String body = mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON).content(
                "{\"query\":\"{\\\"q\\\":1}\",\"resourceResultId\":\"rr-1\",\"status\":\"QUEUED\",\"version\":\"3\",\"metadata\":null,"
                    + "\"picsureId\":null,\"userUuid\":null,\"consents\":null,\"trigger\":null}"
            )
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        Matcher saved = SAVED_BODY.matcher(body);
        assertThat(saved.matches()).as("save body %s", body).isTrue();
        Query row = queryRepo.findById(UUID.fromString(saved.group(1))).orElseThrow();
        assertThat(row.getQueryToVariantUuid()).isNull();
        assertThat(row.isSample()).isFalse();
        assertThat(row.getResultType()).isNull();
        assertThat(generalQueryRepo.count()).isZero();
    }

    @Test
    void readOfAnOwnedRowAnswersWithOwnerSortedConsentsAndRunAfterTheTimestamps() throws Exception {
        mockMvc.perform(
            post("/internal/queries").header(InternalTokenFilter.HEADER, validToken).contentType(MediaType.APPLICATION_JSON).content(
                "{\"query\":\"{\\\"q\\\":1}\",\"status\":\"QUEUED\",\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\","
                    + "\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\",\"consents\":{\"\\\\_consents\\\\\":[\"phs2.c1\",\"phs1.c1\"]},"
                    + "\"trigger\":\"FIRST_SUBMIT\",\"queryToVariantUuid\":\"3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f\"}"
            )
        ).andExpect(status().isCreated());

        String body = mockMvc.perform(
            get("/internal/queries/{picsureId}", "8694e3d4-5cb4-410f-8431-993445e6d3f6").header(InternalTokenFilter.HEADER, validToken)
        ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .startsWith(
                "{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":\"{\\\"q\\\":1}\",\"resourceResultId\":null,"
                    + "\"status\":\"QUEUED\",\"version\":null,\"metadata\":null,\"startTime\":"
            )
            .contains(
                ",\"readyTime\":null,\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\","
                    + "\"consents\":{\"\\\\_consents\\\\\":[\"phs1.c1\",\"phs2.c1\"]},\"runId\":\""
            ).matches(".*,\"runId\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"}$");
    }

    @Test
    void readOfAnOwnerlessRowWritesNullOwnerConsentsAndRun() throws Exception {
        Query saved = queryRepo.save(QueryFixtures.withRandomId());

        mockMvc.perform(get("/internal/queries/{picsureId}", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(
                content().string(
                    "{\"picsureId\":\"" + saved.getUuid() + "\",\"query\":\"\",\"resourceResultId\":null,\"status\":null,\"version\":null,"
                        + "\"metadata\":null,\"startTime\":null,\"readyTime\":null,\"userUuid\":null,\"consents\":null,\"runId\":null}"
                )
            );
    }

    @Test
    void dispatchAnswersWithTheStoredQueryAsOneJsonString() throws Exception {
        Query saved = QueryFixtures.withRandomId();
        saved.setQuery("{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}");
        saved = queryRepo.save(saved);

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"queryJson\":\"{\\\"resourceUUID\\\":\\\"r\\\",\\\"query\\\":\\\"q\\\"}\"}"));
    }

    @Test
    void dispatchOfARowWithNoQueryAnswersWithAnExplicitNull() throws Exception {
        Query saved = queryRepo.save(QueryFixtures.withRandomId());

        mockMvc.perform(get("/internal/queries/{picsureId}/dispatch", saved.getUuid()).header(InternalTokenFilter.HEADER, validToken))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"queryJson\":null}"));
    }

    @Test
    void variantsAnswerWithAnArrayOfTheFourVariantMembersInDeclarationOrder() throws Exception {
        UUID owner = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
        UUID generalQuery = UUID.fromString("3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f");
        Query dated = QueryFixtures.variant(owner, generalQuery, true, "DATAFRAME");
        dated.setCreatedAt(Instant.parse("2026-09-30T14:05:00Z"));
        queryRepo.saveAndFlush(dated);
        Query undated = QueryFixtures.variant(owner, generalQuery, false, null);
        queryRepo.saveAndFlush(undated);

        mockMvc
            .perform(
                get("/internal/queries/variants").header(InternalTokenFilter.HEADER, validToken)
                    .param("queryToVariantUuid", generalQuery.toString()).param("userUuid", owner.toString())
            ).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(
                content().string(
                    "[{\"picsureId\":\"" + dated.getUuid() + "\",\"sample\":true,\"resultType\":\"DATAFRAME\",\"createdAt\":1790777100000},"
                        + "{\"picsureId\":\"" + undated.getUuid() + "\",\"sample\":false,\"resultType\":null,\"createdAt\":null}]"
                )
            );
    }

    @Test
    void handlersDeclareTheSharedRecords() throws Exception {
        Method saveHandler = InternalQueryController.class.getMethod("save", SaveQueryRequest.class);
        Method readHandler = InternalQueryController.class.getMethod("get", UUID.class);
        Method updateHandler = InternalQueryController.class.getMethod("update", UUID.class, UpdateQueryRequest.class);
        Method dispatchHandler = InternalQueryController.class.getMethod("dispatch", UUID.class);
        Method variantsHandler = InternalQueryController.class.getMethod("variants", UUID.class, UUID.class);

        assertThat(saveHandler.getGenericReturnType().getTypeName())
            .isEqualTo("org.springframework.http.ResponseEntity<edu.harvard.dbmi.avillach.domain.SavedQueryReference>");
        assertThat(readHandler.getReturnType()).isEqualTo(StoredQuery.class);
        assertThat(updateHandler.getGenericReturnType().getTypeName()).isEqualTo("org.springframework.http.ResponseEntity<java.lang.Void>");
        assertThat(dispatchHandler.getReturnType()).isEqualTo(DispatchResponse.class);
        assertThat(variantsHandler.getGenericReturnType().getTypeName())
            .isEqualTo("java.util.List<edu.harvard.dbmi.avillach.domain.StoredQueryVariant>");
    }
}
