package edu.harvard.dbmi.avillach.logging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Binds the frozen environment variable names via placeholders in application.yml. There is deliberately no {@code port} component: Spring
 * owns {@code server.port}.
 *
 * <p>Validation lives in the compact constructor rather than in JSR-380 annotations, matching the gateway's GatewaySecurityProperties
 * style.
 */
@ConfigurationProperties(prefix = "picsure.logging")
public record LoggingProperties(
    String apiKey, String app, String platform, String environment, String hostname, String allowedOrigin,
    Map<String, String> jwtClaimMapping
) {

    private static final Set<String> RESERVED_AUDIT_OUTPUT_FIELDS = Set.of(
        "_time", "event_type", "action", "client_type", "caller", "session_id", "logged_in", "app", "platform", "environment", "hostname",
        "request_id", "method", "url", "query_string", "src_ip", "dest_ip", "dest_port", "http_user_agent", "http_content_type", "status",
        "bytes", "duration", "referrer", "metadata", "error"
    );

    public LoggingProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LOGGING_API_KEY environment variable is required");
        }
        app = defaultIfBlank(app, "unknown");
        platform = defaultIfBlank(platform, "unknown");
        environment = defaultIfBlank(environment, "unknown");
        hostname = defaultIfBlank(hostname, systemHostname());
        allowedOrigin = defaultIfBlank(allowedOrigin, "*");
        jwtClaimMapping = (jwtClaimMapping == null) ? JwtClaimMappingConverter.DEFAULT_MAPPING : validatedJwtClaimMapping(jwtClaimMapping);
    }

    private static Map<String, String> validatedJwtClaimMapping(Map<String, String> jwtClaimMapping) {
        LinkedHashMap<String, String> validatedMapping = new LinkedHashMap<>();
        Set<String> outputFields = new HashSet<>();

        for (Map.Entry<String, String> entry : jwtClaimMapping.entrySet()) {
            String inputClaim = entry.getKey();
            String outputField = entry.getValue();
            if (inputClaim == null || inputClaim.isBlank()) {
                throw new IllegalStateException("JWT_CLAIM_MAPPING has invalid input claim name: " + inputClaim);
            }
            if ("logged_in".equals(inputClaim)) {
                throw new IllegalStateException("JWT_CLAIM_MAPPING input claim is reserved: " + inputClaim);
            }
            if (outputField == null || outputField.isBlank()) {
                throw new IllegalStateException(
                    "JWT_CLAIM_MAPPING has invalid output field for input claim " + inputClaim + ": " + outputField
                );
            }
            if (!outputFields.add(outputField)) {
                throw new IllegalStateException("JWT_CLAIM_MAPPING has duplicate output field: " + outputField);
            }
            if (RESERVED_AUDIT_OUTPUT_FIELDS.contains(outputField)) {
                throw new IllegalStateException("JWT_CLAIM_MAPPING output field is reserved: " + outputField);
            }
            validatedMapping.put(inputClaim, outputField);
        }

        return Collections.unmodifiableMap(validatedMapping);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private static String systemHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
