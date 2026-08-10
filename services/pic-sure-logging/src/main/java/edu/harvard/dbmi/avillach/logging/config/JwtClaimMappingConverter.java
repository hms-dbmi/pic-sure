package edu.harvard.dbmi.avillach.logging.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationPropertiesBinding
public class JwtClaimMappingConverter implements Converter<String, Map<String, String>> {

    /** Must match the historic AppConfig.DEFAULT_JWT_CLAIM_MAPPING exactly. */
    public static final Map<String, String> DEFAULT_MAPPING = Map.ofEntries(
        Map.entry("sub", "subject"), Map.entry("email", "user_email"), Map.entry("name", "user_name"), Map.entry("userid", "user_id"),
        Map.entry("preferred_username", "preferred_username"), Map.entry("org", "user_org"), Map.entry("country_name", "user_country_name"),
        Map.entry("nih_ico", "nih_ico"), Map.entry("eRA_commons_id", "eRA_commons_id"),
        Map.entry("user_permission_group", "user_permission_group"), Map.entry("uuid", "uuid"), Map.entry("roles", "roles"),
        Map.entry("idp", "user_id_provider"), Map.entry("cadr_name", "cadr_name")
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Map<String, String> convert(@NonNull String source) {
        if (source.isBlank()) {
            return DEFAULT_MAPPING;
        }
        Map<String, String> parsed;
        try {
            parsed = MAPPER.readValue(source, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("JWT_CLAIM_MAPPING must be valid JSON object, got: " + source, e);
        }
        // Fail here with the variable's name; otherwise the immutable copy in LoggingProperties
        // dies later with a bare NullPointerException that names nothing.
        if (parsed.containsValue(null)) {
            throw new IllegalStateException("JWT_CLAIM_MAPPING must not contain null values, got: " + source);
        }
        return parsed;
    }
}
