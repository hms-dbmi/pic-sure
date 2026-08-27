package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class BannerPresentationHasher {

    private final ObjectMapper objectMapper;

    public BannerPresentationHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(PublishBannerRequest request) {
        List<String> fields = List.of(
            request.htmlContent(), normalizeTitle(request.title()), request.appearance().name(), request.icon().name(),
            Boolean.toString(request.dismissible()), request.audience().name(), request.placement().name(),
            canonicalJson(request.pageTargets())
        );
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String field : fields) {
                    byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to compute banner presentation hash", e);
        }
    }

    static String normalizeTitle(String title) {
        return title == null ? "" : title.strip();
    }

    private String canonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Page targets cannot be normalized", e);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode normalized = objectMapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), canonicalize(entry.getValue())));
            fields.forEach(normalized::set);
            return normalized;
        }
        if (value.isArray()) {
            List<JsonNode> entries = new ArrayList<>();
            value.forEach(entry -> entries.add(canonicalize(entry)));
            entries.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode normalized = objectMapper.createArrayNode();
            entries.forEach(normalized::add);
            return normalized;
        }
        return value.deepCopy();
    }
}
