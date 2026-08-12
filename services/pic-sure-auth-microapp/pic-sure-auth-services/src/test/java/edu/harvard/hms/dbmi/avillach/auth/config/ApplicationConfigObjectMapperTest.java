package edu.harvard.hms.dbmi.avillach.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PlatformApiKeyRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyCreationResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The app-defined ObjectMapper bean replaces Boot's auto-configured one, so java.time support has to be registered by hand. These tests pin
 * the wire contract of the API-key DTOs (the first PSAMA payloads using Instant) and the legacy epoch-millis rendering of java.util.Date.
 */
public class ApplicationConfigObjectMapperTest {

    private final ObjectMapper objectMapper = new ApplicationConfig(null).objectMapper();

    @Test
    public void testCreationResponseSerializesWithIsoInstant() throws Exception {
        Instant expiresAt = Instant.parse("2026-10-11T12:13:14Z");
        ApiKeyCreationResponse response = new ApiKeyCreationResponse(
            "picsure_0000000000000000000000000000000000000000000", UUID.randomUUID(), "00000000", ApiKeyType.USER, expiresAt
        );

        String json = objectMapper.writeValueAsString(response);

        assertTrue(json.contains("\"2026-10-11T12:13:14Z\""), json);
        assertTrue(json.contains("picsure_"), json);
    }

    @Test
    public void testPlatformRequestDeserializesIsoInstant() throws Exception {
        PlatformApiKeyRequest request = objectMapper
            .readValue("{\"name\":\"Partner\",\"email\":\"a@b.com\",\"expiresAt\":\"2027-01-01T00:00:00Z\"}", PlatformApiKeyRequest.class);

        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), request.expiresAt());

        PlatformApiKeyRequest withoutExpiry =
            objectMapper.readValue("{\"name\":\"Partner\",\"email\":\"a@b.com\"}", PlatformApiKeyRequest.class);
        assertNull(withoutExpiry.expiresAt());
    }

    @Test
    public void testLegacyDateStillSerializesAsEpochMillis() throws Exception {
        record LegacyPayload(Date dateUpdated) {
        }

        String json = objectMapper.writeValueAsString(new LegacyPayload(new Date(1234567890123L)));

        assertEquals("{\"dateUpdated\":1234567890123}", json);
    }
}
