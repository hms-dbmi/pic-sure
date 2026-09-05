package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class BannerPresentationHasher {

    private final ObjectMapper objectMapper;

    public BannerPresentationHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(BannerOccurrence banner) {
        if (banner == null) {
            throw new IllegalArgumentException("Banner occurrence is required");
        }
        List<String> fields = List.of(
            required(banner.getHtmlContent(), "htmlContent"), normalizeTitle(banner.getTitle()),
            required(banner.getAppearance(), "appearance").name(), required(banner.getIcon(), "icon").name(),
            Boolean.toString(banner.isDismissible()), required(banner.getAudience(), "audience").name(),
            required(banner.getPlacement(), "placement").name(),
            canonicalJson(BannerPageTargets.normalize(required(banner.getPageTargets(), "pageTargets")))
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to compute banner presentation hash", e);
        }
    }

    static String normalizeTitle(String title) {
        return title == null ? "" : title.strip();
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Banner " + field + " is required");
        }
        return value;
    }

    private String canonicalJson(List<BannerPageTarget> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Page targets cannot be normalized", e);
        }
    }

}
