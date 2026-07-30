package edu.harvard.dbmi.avillach.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.contracts.info.QueryFormat;
import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
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
    void shouldDefaultNullCollectionsToEmpty() {
        assertEquals(Map.of(), new QueryStatusResponse(null, null, null, null, 0L, 0L, 0L, 0L, null).resultMetadata());
        assertEquals(List.of(), new PaginatedResponse<String>(null, 0, 0).results());
        assertEquals(List.of(), new ResourceInfo(null, null, null).queryFormats());
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
