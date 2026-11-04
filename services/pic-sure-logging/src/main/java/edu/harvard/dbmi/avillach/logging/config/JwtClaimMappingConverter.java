package edu.harvard.dbmi.avillach.logging.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationPropertiesBinding
public class JwtClaimMappingConverter implements Converter<String, Map<String, String>> {

    /** Must match the historic AppConfig.DEFAULT_JWT_CLAIM_MAPPING exactly. */
    public static final Map<String, String> DEFAULT_MAPPING;

    static {
        LinkedHashMap<String, String> defaultMapping = new LinkedHashMap<>();
        defaultMapping.put("sub", "subject");
        defaultMapping.put("email", "user_email");
        defaultMapping.put("name", "user_name");
        defaultMapping.put("userid", "user_id");
        defaultMapping.put("preferred_username", "preferred_username");
        defaultMapping.put("org", "user_org");
        defaultMapping.put("country_name", "user_country_name");
        defaultMapping.put("nih_ico", "nih_ico");
        defaultMapping.put("eRA_commons_id", "eRA_commons_id");
        defaultMapping.put("user_permission_group", "user_permission_group");
        defaultMapping.put("uuid", "uuid");
        defaultMapping.put("roles", "roles");
        defaultMapping.put("idp", "user_id_provider");
        defaultMapping.put("cadr_name", "cadr_name");
        DEFAULT_MAPPING = Collections.unmodifiableMap(defaultMapping);
    }

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
        // Fail at the binding boundary with the variable's name; LoggingProperties independently
        // validates mappings supplied through direct construction.
        if (parsed.containsValue(null)) {
            throw new IllegalStateException("JWT_CLAIM_MAPPING must not contain null values, got: " + source);
        }
        return parsed;
    }
}
