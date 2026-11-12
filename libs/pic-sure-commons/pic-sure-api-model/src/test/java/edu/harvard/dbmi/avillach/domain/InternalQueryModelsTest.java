package edu.harvard.dbmi.avillach.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the JSON of the records the operations service and the query service exchange over {@code /internal/queries}. The mapper is a plain
 * {@link ObjectMapper}, which fails on unknown properties, so a request record that binds a body with an extra member proves its own
 * {@code ignoreUnknown} setting and not a lenient mapper.
 */
public class InternalQueryModelsTest {

    private static final UUID ID = UUID.fromString("8694e3d4-5cb4-410f-8431-993445e6d3f6");
    private static final UUID OWNER = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID RUN = UUID.fromString("0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77");
    private static final UUID GENERAL_QUERY = UUID.fromString("3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f");
    private static final Map<String, List<String>> CONSENTS = Map.of("\\_consents\\", List.of("phs1.c1", "phs2.c1"));
    private static final String CONSENTS_JSON = "{\"\\\\_consents\\\\\":[\"phs1.c1\",\"phs2.c1\"]}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void savedQueryReferenceIsOnePicsureIdMember() throws JsonProcessingException {
        assertEquals("{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"}", mapper.writeValueAsString(new SavedQueryReference(ID)));
        assertEquals(
            new SavedQueryReference(ID),
            mapper.readValue("{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"}", SavedQueryReference.class)
        );
    }

    @Test
    public void dispatchResponseCarriesTheQueryAsAString() throws JsonProcessingException {
        assertEquals("{\"queryJson\":\"{\\\"query\\\":\\\"q\\\"}\"}", mapper.writeValueAsString(new DispatchResponse("{\"query\":\"q\"}")));
    }

    @Test
    public void dispatchResponseWritesAnExplicitNull() throws JsonProcessingException {
        assertEquals("{\"queryJson\":null}", mapper.writeValueAsString(new DispatchResponse(null)));
    }

    @Test
    public void storedQueryWritesEveryMemberInDeclarationOrderNullsIncluded() throws JsonProcessingException {
        StoredQuery stored = new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", null, 1790777100000L, null);

        assertEquals(
            "{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":\"{}\",\"resourceResultId\":\"rr-1\","
                + "\"status\":\"AVAILABLE\",\"version\":\"3\",\"metadata\":null,\"startTime\":1790777100000,\"readyTime\":null,"
                + "\"userUuid\":null,\"consents\":null,\"runId\":null}",
            mapper.writeValueAsString(stored)
        );
    }

    @Test
    public void storedQueryCarriesOwnerConsentsAndLatestRun() throws JsonProcessingException {
        StoredQuery stored = new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", null, 1790777100000L, null, OWNER, CONSENTS, RUN);
        String json = "{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":\"{}\",\"resourceResultId\":\"rr-1\","
            + "\"status\":\"AVAILABLE\",\"version\":\"3\",\"metadata\":null,\"startTime\":1790777100000,\"readyTime\":null,"
            + "\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\",\"consents\":" + CONSENTS_JSON
            + ",\"runId\":\"0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77\"}";

        assertEquals(json, mapper.writeValueAsString(stored));
        assertEquals(stored, mapper.readValue(json, StoredQuery.class));
    }

    @Test
    public void storedQueryWithoutOwnerLeavesOwnerConsentsAndRunNull() {
        StoredQuery stored = new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", "YWJj", 1790777100000L, 1790777160000L);

        assertNull(stored.userUuid());
        assertNull(stored.consents());
        assertNull(stored.runId());
    }

    @Test
    public void storedQueryBindsThroughItsCanonicalConstructor() throws JsonProcessingException {
        StoredQuery stored = mapper.readValue(
            "{\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":\"{}\",\"resourceResultId\":\"rr-1\","
                + "\"status\":\"AVAILABLE\",\"version\":\"3\",\"metadata\":\"YWJj\",\"startTime\":1790777100000,\"readyTime\":1790777160000}",
            StoredQuery.class
        );

        assertEquals(new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", "YWJj", 1790777100000L, 1790777160000L), stored);
    }

    @Test
    public void storedQueryWithoutTimingLeavesBothTimestampsNull() {
        StoredQuery stored = new StoredQuery(ID, "{}", "rr-1", "AVAILABLE", "3", "YWJj");

        assertNull(stored.startTime());
        assertNull(stored.readyTime());
    }

    @Test
    public void saveQueryRequestWritesEveryMemberAndIgnoresUnknownOnes() throws JsonProcessingException {
        assertEquals(
            "{\"query\":\"{}\",\"resourceResultId\":null,\"status\":\"QUEUED\",\"version\":\"3\",\"metadata\":null,"
                + "\"picsureId\":null,\"userUuid\":null,\"consents\":null,\"trigger\":null,\"queryToVariantUuid\":null,"
                + "\"sample\":null,\"resultType\":null}",
            mapper.writeValueAsString(new SaveQueryRequest("{}", null, "QUEUED", "3", null))
        );
        assertEquals(
            new SaveQueryRequest("{}", null, "QUEUED", null, null),
            mapper.readValue("{\"query\":\"{}\",\"status\":\"QUEUED\",\"resourceCredentials\":{}}", SaveQueryRequest.class)
        );
    }

    @Test
    public void saveQueryRequestCarriesIdOwnerConsentsAndTrigger() throws JsonProcessingException {
        SaveQueryRequest request = new SaveQueryRequest("{}", "rr-1", "QUEUED", "3", null, ID, OWNER, CONSENTS, "FIRST_SUBMIT");
        String json = "{\"query\":\"{}\",\"resourceResultId\":\"rr-1\",\"status\":\"QUEUED\",\"version\":\"3\",\"metadata\":null,"
            + "\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\","
            + "\"consents\":" + CONSENTS_JSON + ",\"trigger\":\"FIRST_SUBMIT\",\"queryToVariantUuid\":null,\"sample\":null,"
            + "\"resultType\":null}";

        assertEquals(json, mapper.writeValueAsString(request));
        assertEquals(request, mapper.readValue(json, SaveQueryRequest.class));
    }

    @Test
    public void saveQueryRequestCarriesTheGeneralQuerySampleAndResultType() throws JsonProcessingException {
        SaveQueryRequest request =
            new SaveQueryRequest("{}", "rr-1", "QUEUED", "3", null, ID, OWNER, CONSENTS, "FIRST_SUBMIT", GENERAL_QUERY, true, "DATAFRAME");
        String json = "{\"query\":\"{}\",\"resourceResultId\":\"rr-1\",\"status\":\"QUEUED\",\"version\":\"3\",\"metadata\":null,"
            + "\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\","
            + "\"consents\":" + CONSENTS_JSON + ",\"trigger\":\"FIRST_SUBMIT\","
            + "\"queryToVariantUuid\":\"3d2f7c1a-9b4e-5c6d-8e7f-0a1b2c3d4e5f\",\"sample\":true,\"resultType\":\"DATAFRAME\"}";

        assertEquals(json, mapper.writeValueAsString(request));
        assertEquals(request, mapper.readValue(json, SaveQueryRequest.class));
        assertEquals(GENERAL_QUERY, request.queryToVariantUuid());
        assertEquals(Boolean.TRUE, request.sample());
        assertEquals("DATAFRAME", request.resultType());
    }

    @Test
    public void saveQueryRequestWrittenBeforeTheVariantMembersStillBinds() throws JsonProcessingException {
        String writtenBefore = "{\"query\":\"{}\",\"resourceResultId\":\"rr-1\",\"status\":\"QUEUED\",\"version\":\"3\",\"metadata\":null,"
            + "\"picsureId\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"userUuid\":\"5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\","
            + "\"consents\":" + CONSENTS_JSON + ",\"trigger\":\"FIRST_SUBMIT\"}";

        SaveQueryRequest bound = mapper.readValue(writtenBefore, SaveQueryRequest.class);

        assertEquals(new SaveQueryRequest("{}", "rr-1", "QUEUED", "3", null, ID, OWNER, CONSENTS, "FIRST_SUBMIT"), bound);
        assertNull(bound.queryToVariantUuid());
        assertNull(bound.sample());
        assertNull(bound.resultType());
    }

    @Test
    public void updateQueryRequestCarriesTheRunId() throws JsonProcessingException {
        UpdateQueryRequest request = new UpdateQueryRequest("AVAILABLE", null, null, null, null, RUN);
        String json = "{\"status\":\"AVAILABLE\",\"resourceResultId\":null,\"metadata\":null,\"query\":null,\"version\":null,"
            + "\"runId\":\"0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77\"}";

        assertEquals(json, mapper.writeValueAsString(request));
        assertEquals(request, mapper.readValue(json, UpdateQueryRequest.class));
    }

    @Test
    public void updateQueryRequestWritesEveryMemberAndIgnoresUnknownOnes() throws JsonProcessingException {
        assertEquals(
            "{\"status\":\"AVAILABLE\",\"resourceResultId\":\"rr-1\",\"metadata\":null,\"query\":null,\"version\":null,\"runId\":null}",
            mapper.writeValueAsString(new UpdateQueryRequest("AVAILABLE", "rr-1", null, null, null))
        );
        assertEquals(
            new UpdateQueryRequest("AVAILABLE", null, null, null, null),
            mapper.readValue("{\"status\":\"AVAILABLE\",\"picsureId\":\"ignored\"}", UpdateQueryRequest.class)
        );
    }
}
