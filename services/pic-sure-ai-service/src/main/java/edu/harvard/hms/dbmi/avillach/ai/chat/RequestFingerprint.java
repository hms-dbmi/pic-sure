package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * A hash of everything about a chat request except its {@code requestId}: the same hash under another {@code requestId} is the same
 * message sent twice, and the same {@code requestId} under another hash is a reused key.
 */
final class RequestFingerprint {

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private RequestFingerprint() {}

    static String of(ChatRequest request) {
        try {
            // Object fields are sorted by writing through plain maps, which ORDER_MAP_ENTRIES_BY_KEYS covers and a JsonNode tree is not.
            Object canonical = JSON.convertValue(
                Arrays.asList(request.conversationId(), request.message(), request.query(), request.facets(), request.search()), Object.class
            );
            String content = JSON.writeValueAsString(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A request that was just parsed can be written back out", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
