package edu.harvard.dbmi.avillach.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import edu.harvard.dbmi.avillach.contracts.audit.AuditAccepted;
import edu.harvard.dbmi.avillach.contracts.audit.AuditEvent;
import edu.harvard.dbmi.avillach.contracts.audit.RequestInfo;
import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionRequest;
import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionResponse;
import edu.harvard.dbmi.avillach.contracts.auth.UserConsentsResponse;
import edu.harvard.dbmi.avillach.contracts.info.QueryFormat;
import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
import edu.harvard.dbmi.avillach.contracts.internal.DispatchResponse;
import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryRequest;
import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryResponse;
import edu.harvard.dbmi.avillach.contracts.internal.StoredQuery;
import edu.harvard.dbmi.avillach.contracts.internal.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.PaginatedResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Every wire contract in this module must survive a JSON -> record -> JSON round trip with no field lost, added, or renamed. These fixtures
 * are the authoritative description of the wire shape: if a change here forces a fixture edit, it is a breaking API change.
 */
class FixtureRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldRoundTripQueryStatusResponse() throws IOException {
        QueryStatusResponse response = assertRoundTrip("query-status-response.json", QueryStatusResponse.class);

        assertEquals(PicSureStatus.AVAILABLE, response.status());
        assertEquals("COMPLETE", response.resourceStatus());
        assertEquals(Map.of("queryJson", "{}"), response.resultMetadata());
    }

    @Test
    void shouldRoundTripSearchRequest() throws IOException {
        SearchRequest request = assertRoundTrip("search-request.json", SearchRequest.class);

        assertEquals("asthma", request.query());
    }

    @Test
    void shouldRoundTripPaginatedResponse() throws IOException {
        JavaType type = MAPPER.getTypeFactory()
            .constructParametricType(PaginatedResponse.class, MAPPER.constructType(new TypeReference<Map<String, Object>>() {}));
        PaginatedResponse<Map<String, Object>> response = assertRoundTrip("paginated-response.json", type);

        assertEquals(2, response.results().size());
        assertEquals(0, response.page());
        assertEquals(2, response.total());
    }

    @Test
    void shouldRoundTripSignedUrlResponse() throws IOException {
        SignedUrlResponse response = assertRoundTrip("signed-url-response.json", SignedUrlResponse.class);

        assertEquals("https://example.com/results/42?X-Amz-Signature=abc123", response.signedUrl());
    }

    @Test
    void shouldRoundTripQueryFormat() throws IOException {
        QueryFormat queryFormat = assertRoundTrip("query-format.json", QueryFormat.class);

        assertEquals("HPDS Query Format", queryFormat.name());
        assertEquals(1, queryFormat.examples().size());
    }

    @Test
    void shouldRoundTripResourceInfo() throws IOException {
        ResourceInfo resourceInfo = assertRoundTrip("resource-info.json", ResourceInfo.class);

        assertEquals("HPDS Resource", resourceInfo.name());
        assertEquals(1, resourceInfo.queryFormats().size());
        assertNotNull(resourceInfo.id());
    }

    @Test
    void shouldRoundTripIntrospectionRequest() throws IOException {
        IntrospectionRequest request = assertRoundTrip("introspection-request.json", IntrospectionRequest.class);

        assertEquals("/hpds/auth/v3/query", request.request().targetService());
        assertEquals("COUNT", request.request().query().get("expectedResultType").asText());
    }

    @Test
    void shouldRoundTripIntrospectionResponse() throws IOException {
        IntrospectionResponse response = assertRoundTrip("introspection-response.json", IntrospectionResponse.class);

        assertTrue(response.active());
        assertEquals("6ac1b1df-1c66-4b5c-8f5a-1f5c8c1e0a11", response.userId());
        assertEquals(List.of("ADMIN", "PRIV_MANAGED_phs000007_c1"), response.privileges());
        assertTrue(response.query().isObject(), "the consent-mutated query must stay a JSON object");
    }

    /**
     * The user's study authorizations. The map is keyed by CONCEPT PATH -- the escaped {@code \_consents\} form the query layer uses -- and
     * its values are the consent identifiers verbatim. An earlier client was documented against {@code {"phs000001": ["c1"]}}, which is
     * wrong on both counts; this fixture is the byte-for-byte copy both clients test against, so the shape cannot drift again.
     */
    @Test
    void shouldRoundTripUserConsentsResponse() throws IOException {
        UserConsentsResponse response = assertRoundTrip("user-consents-response.json", UserConsentsResponse.class);

        assertEquals("6ac1b1df-1c66-4b5c-8f5a-1f5c8c1e0a11", response.userId());
        assertEquals(
            Set.of("phs000007.c1", "phs000179.c2", "open_access-1000Genomes"), response.consents().get("\\_consents\\"),
            "consent identifiers ride verbatim, keyed by the escaped concept path"
        );
        assertEquals(Set.of("phs000007.c1"), response.consents().get("\\_harmonized_consent\\"));
        assertEquals(Set.of("phs000179.c2", "open_access-1000Genomes"), response.consents().get("\\_topmed_consents\\"));
    }

    /**
     * The persisted row's uuid is a storage detail of PSAMA's {@code user_consents} table, never part of the answer. It came off the wire
     * with this record and must not creep back: a client that starts reading it would bind to PSAMA's schema.
     */
    @Test
    void shouldNotCarryThePersistedRowUuid() throws IOException {
        String json = MAPPER.writeValueAsString(new UserConsentsResponse("user-1", Map.of()));

        assertFalse(json.contains("uuid"), "the user_consents row id must not be on the wire: " + json);
        assertEquals("{\"userId\":\"user-1\",\"consents\":{}}", json);
    }

    /**
     * SECURITY: absence and null are not interchangeable for a JsonNode component. Jackson binds an explicit JSON null to NullNode and only
     * an absent field to Java null, so callers that test the mutated query with {@code query() != null} -- which is how the gateway decides
     * whether to swap the caller's request body -- see a present query on EVERY response the moment PSAMA emits {@code "query": null}. This
     * is the whole reason IntrospectionResponse is NON_NULL.
     */
    @Test
    void shouldDistinguishAnAbsentQueryFromAnExplicitNullQuery() throws IOException {
        IntrospectionResponse absent = MAPPER.readValue("{\"active\":true}", IntrospectionResponse.class);
        assertNull(absent.query(), "an absent query must read back as Java null, not NullNode");

        IntrospectionResponse explicitNull = MAPPER.readValue("{\"active\":true,\"query\":null}", IntrospectionResponse.class);
        assertNotNull(explicitNull.query(), "Jackson binds an explicit JSON null to NullNode; this is the trap NON_NULL avoids");
        assertTrue(explicitNull.query().isNull());
    }

    /**
     * The serialized counterpart: an unmutated verdict must omit the query key entirely, which is what PSAMA's wire looked like before the
     * response was typed. Emitting it as null would trip every {@code query() != null} check downstream.
     */
    @Test
    void shouldOmitNullFieldsWhenSerializingAVerdict() throws IOException {
        IntrospectionResponse unmutated =
            new IntrospectionResponse(true, "user-1", "saml|foo", null, List.of("ADMIN"), List.of(), false, null, null);

        String json = MAPPER.writeValueAsString(unmutated);

        assertFalse(json.contains("\"query\""), "an unmutated verdict must not carry a query key at all: " + json);
        assertFalse(json.contains("\"token\""), "the refreshed-token key must be absent when no token was issued: " + json);
        assertFalse(json.contains("\"email\""), json);
        assertTrue(json.contains("\"active\":true"), json);
        assertTrue(json.contains("\"tokenRefreshed\":false"), "primitives stay on the wire regardless of inclusion: " + json);
        assertNull(MAPPER.readValue(json, IntrospectionResponse.class).query());
    }

    /**
     * The body carries more than this record models -- PSAMA's TokenIntrospectionResponse adds an unmodelled {@code message} -- and a
     * future PSAMA may add fields before this contract learns them. Unknown properties must not break deserialization.
     */
    @Test
    void shouldIgnoreUnknownIntrospectionResponseProperties() throws IOException {
        IntrospectionResponse response =
            MAPPER.readValue("{\"active\":true,\"exp\":1893456000,\"iat\":1893452400,\"message\":\"ok\"}", IntrospectionResponse.class);

        assertTrue(response.active());
    }

    /**
     * {@code userId} carried a {@code @JsonAlias("uuid")} while PSAMA and the gateway could be at different versions. They deploy as one
     * unit, PSAMA writes this record, and nothing in the introspection flow emits a {@code uuid} key any more -- the JWT claim of that name
     * is read by {@code TokenService#userId} and written out as {@code userId}. The alias is gone, so a stray {@code uuid} is just another
     * unmodelled property on a tolerant reader: ignored, never bound.
     */
    @Test
    void shouldNotBindTheLegacyUuidKeyToUserId() throws IOException {
        IntrospectionResponse response =
            MAPPER.readValue("{\"active\":true,\"uuid\":\"6ac1b1df-1c66-4b5c-8f5a-1f5c8c1e0a11\"}", IntrospectionResponse.class);

        assertNull(response.userId(), "the uuid alias is gone; only the userId key names the user");
    }

    @Test
    void shouldRoundTripSaveQueryRequest() throws IOException {
        SaveQueryRequest request = assertRoundTrip("save-query-request.json", SaveQueryRequest.class);

        assertEquals(PicSureStatus.QUEUED, request.status());
        assertEquals("hpds-result-4471", request.resourceResultId());
        assertEquals("v3", request.version());
        assertTrue(request.query().startsWith("{"), "the stored query stays an opaque JSON string, never a nested object");
    }

    @Test
    void shouldRoundTripUpdateQueryRequest() throws IOException {
        UpdateQueryRequest request = assertRoundTrip("update-query-request.json", UpdateQueryRequest.class);

        assertEquals(PicSureStatus.AVAILABLE, request.status());
        assertEquals("hpds-result-4471", request.resourceResultId());
    }

    /**
     * The persisted status travels as the enum NAME, not its ordinal: the operations service writes {@code "AVAILABLE"} on the wire and any
     * shift to numbers would silently re-map every stored query.
     */
    @Test
    void shouldRoundTripStoredQuery() throws IOException {
        StoredQuery stored = assertRoundTrip("stored-query.json", StoredQuery.class);

        assertEquals(PicSureStatus.AVAILABLE, stored.status());
        assertEquals(UUID.fromString("0f0d1d5c-6b3f-4d51-9a1e-2c9b6f7f8a10"), stored.picsureId());
        assertTrue(read("stored-query.json").contains("\"status\": \"AVAILABLE\""), "status must be the enum name on the wire");
    }

    @Test
    void shouldRoundTripSaveQueryResponse() throws IOException {
        SaveQueryResponse response = assertRoundTrip("save-query-response.json", SaveQueryResponse.class);

        assertEquals(UUID.fromString("0f0d1d5c-6b3f-4d51-9a1e-2c9b6f7f8a10"), response.picsureId());
    }

    @Test
    void shouldRoundTripDispatchResponse() throws IOException {
        DispatchResponse response = assertRoundTrip("dispatch-response.json", DispatchResponse.class);

        assertTrue(response.queryJson().startsWith("{"), "queryJson is a JSON STRING, not a nested object");
    }

    @Test
    void shouldRoundTripAuditEvent() throws IOException {
        AuditEvent event = assertRoundTrip("audit-event.json", AuditEvent.class);

        assertEquals("api_request", event.eventType());
        assertEquals("gateway", event.clientType());
        assertEquals("1f5c8c1e-0a11-4b5c-8f5a-6ac1b1df1c66", event.sessionId());
        assertEquals("POST", event.request().method());
        assertEquals(1, event.metadata().size());
        assertEquals(2, event.error().size());
    }

    @Test
    void shouldRoundTripRequestInfo() throws IOException {
        RequestInfo request = assertRoundTrip("request-info.json", RequestInfo.class);

        assertEquals("b7f0c1a2-3d4e-4f50-8a6b-9c0d1e2f3a4b", request.requestId());
        assertEquals("format=json", request.queryString());
        assertEquals(8080, request.destPort());
        assertEquals(2048L, request.bytes());
    }

    /**
     * The audit emitters are fire-and-forget filters that fill in a handful of the thirteen request fields and leave the rest null. Those
     * nulls have never been on the wire -- the client library's own copy of these models declared NON_NULL -- and putting them there now
     * would inflate every audit POST and hand the collector a wall of null-valued keys to index. Inclusion is part of the contract.
     */
    @Test
    void shouldOmitNullFieldsWhenSerializingAnAuditEvent() throws IOException {
        RequestInfo sparse =
            new RequestInfo(null, "POST", "/picsure/query/sync", null, null, null, null, null, null, 200, null, null, null);
        AuditEvent event = new AuditEvent("api_request", "query.sync", null, null, null, sparse, null, null);

        String json = MAPPER.writeValueAsString(event);

        assertEquals(
            "{\"event_type\":\"api_request\",\"action\":\"query.sync\","
                + "\"request\":{\"method\":\"POST\",\"url\":\"/picsure/query/sync\",\"status\":200}}",
            json
        );
    }

    @Test
    void shouldRoundTripAuditAccepted() throws IOException {
        AuditAccepted accepted = assertRoundTrip("audit-accepted.json", AuditAccepted.class);

        assertEquals("accepted", accepted.status());
    }

    /**
     * The audit models use snake_case on the wire; a camelCase payload is NOT the contract and must leave the fields unset rather than
     * silently binding.
     */
    @Test
    void shouldBindAuditFieldsOnlyBySnakeCaseNames() throws IOException {
        AuditEvent event = MAPPER.readValue("{\"eventType\":\"api_request\",\"event_type\":\"real\"}", AuditEvent.class);

        assertEquals("real", event.eventType());
    }

    /**
     * Audit intake is tolerant by design: emitters across services add keys ahead of the collector learning about them, and an audit event
     * must never be the reason a request fails.
     */
    @Test
    void shouldIgnoreUnknownAuditProperties() throws IOException {
        AuditEvent event = MAPPER.readValue("{\"event_type\":\"api_request\",\"tenant\":\"bch\"}", AuditEvent.class);
        RequestInfo request = MAPPER.readValue("{\"method\":\"GET\",\"http_version\":\"2\"}", RequestInfo.class);

        assertEquals("api_request", event.eventType());
        assertEquals("GET", request.method());
        assertNull(event.request());
    }

    /**
     * Every contract EXCEPT the deliberately tolerant readers ({@code IntrospectionResponse}, the audit models) must reject unknown
     * properties, so a caller sending a field the contract does not model gets a 400 instead of silent data loss. This mirrors the
     * reactor-wide {@code StrictWebDeserializationConfig} that turns the same feature on for every service's web ObjectMapper.
     */
    @Test
    void shouldRejectUnknownPropertiesOnStrictContracts() {
        assertRejectsUnknownProperty("{\"picsureId\":null,\"typo\":1}", StoredQuery.class);
        assertRejectsUnknownProperty("{\"query\":\"{}\",\"typo\":1}", SaveQueryRequest.class);
        assertRejectsUnknownProperty("{\"status\":\"QUEUED\",\"typo\":1}", UpdateQueryRequest.class);
        assertRejectsUnknownProperty("{\"picsureId\":null,\"typo\":1}", SaveQueryResponse.class);
        assertRejectsUnknownProperty("{\"queryJson\":\"{}\",\"typo\":1}", DispatchResponse.class);
        assertRejectsUnknownProperty("{\"status\":\"accepted\",\"typo\":1}", AuditAccepted.class);
        assertRejectsUnknownProperty("{\"resourceStatus\":\"COMPLETE\",\"typo\":1}", QueryStatusResponse.class);
        assertRejectsUnknownProperty("{\"query\":\"asthma\",\"typo\":1}", SearchRequest.class);
        assertRejectsUnknownProperty("{\"signedUrl\":\"https://x\",\"typo\":1}", SignedUrlResponse.class);
        assertRejectsUnknownProperty("{\"name\":\"x\",\"typo\":1}", QueryFormat.class);
        assertRejectsUnknownProperty("{\"name\":\"x\",\"typo\":1}", ResourceInfo.class);
        assertRejectsUnknownProperty("{\"userId\":\"u\",\"typo\":1}", UserConsentsResponse.class);
    }

    @Test
    void shouldDefaultNullCollectionsToEmpty() {
        assertEquals(Map.of(), new QueryStatusResponse(null, null, null, null, 0L, 0L, 0L, 0L, null).resultMetadata());
        assertEquals(List.of(), new PaginatedResponse<String>(null, 0, 0).results());
        assertEquals(List.of(), new ResourceInfo(null, null, null).queryFormats());
        assertEquals(Map.of(), new UserConsentsResponse(null, null).consents());
    }

    /**
     * The ordinal order of this enum is persisted by callers, so reordering it silently rewrites stored statuses.
     */
    @Test
    void shouldPreservePicSureStatusOrder() {
        assertArrayEquals(
            new PicSureStatus[] {PicSureStatus.QUEUED, PicSureStatus.PENDING, PicSureStatus.ERROR, PicSureStatus.AVAILABLE},
            PicSureStatus.values()
        );
    }

    private void assertRejectsUnknownProperty(String json, Class<?> type) {
        assertThrows(
            UnrecognizedPropertyException.class, () -> MAPPER.readValue(json, type),
            type.getSimpleName() + " must not silently swallow properties it does not model"
        );
    }

    private <T> T assertRoundTrip(String fixture, Class<T> type) throws IOException {
        return assertRoundTrip(fixture, MAPPER.constructType(type));
    }

    private <T> T assertRoundTrip(String fixture, JavaType type) throws IOException {
        String json = read(fixture);
        T value = MAPPER.readValue(json, type);
        assertEquals(MAPPER.readTree(json), MAPPER.readTree(MAPPER.writeValueAsString(value)), fixture + " did not survive a round trip");
        return value;
    }

    private String read(String fixture) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/" + fixture)) {
            assertNotNull(in, "missing fixture: fixtures/" + fixture);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
