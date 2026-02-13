package edu.harvard.dbmi.avillach.logging.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.util.Map;
import java.util.function.Function;

public record AppConfig(
    String auditApiKey,
    String app,
    String platform,
    String environment,
    String hostname,
    int port,
    String allowedOrigin,
    Map<String, String> jwtClaimMapping
) {

    private static final Map<String, String> DEFAULT_JWT_CLAIM_MAPPING = Map.ofEntries(
        Map.entry("sub", "subject"),
        Map.entry("email", "user_email"),
        Map.entry("name", "user_name"),
        Map.entry("user_id", "user_id"),
        Map.entry("org", "user_org"),
        Map.entry("country_name", "user_country_name"),
        Map.entry("nih_ico", "nih_ico"),
        Map.entry("eRA_commons_id", "eRA_commons_id"),
        Map.entry("permission_group", "user_permission_group"),
        Map.entry("session_id", "session_id"),
        Map.entry("uuid", "uuid"),
        Map.entry("roles", "roles"),
        Map.entry("logged_in", "logged_in"),
        Map.entry("idp", "user_id_provider"),
        Map.entry("cadr_name", "cadr_name")
    );

    public static AppConfig fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    public static AppConfig fromEnvironment(Function<String, String> env) {
        String auditApiKey = env.apply("AUDIT_API_KEY");
        if (auditApiKey == null || auditApiKey.isBlank()) {
            throw new IllegalStateException("AUDIT_API_KEY environment variable is required");
        }

        String app = getOrDefault(env, "APP", "unknown");
        String platform = getOrDefault(env, "PLATFORM", "unknown");
        String environment = getOrDefault(env, "ENVIRONMENT", "unknown");
        String hostname = getOrDefault(env, "HOSTNAME", getSystemHostname());
        String allowedOrigin = getOrDefault(env, "ALLOWED_ORIGIN", "*");

        int port = parsePort(env.apply("PORT"));

        Map<String, String> jwtClaimMapping = parseJwtClaimMapping(env.apply("JWT_CLAIM_MAPPING"));

        return new AppConfig(auditApiKey, app, platform, environment, hostname, port, allowedOrigin, jwtClaimMapping);
    }

    private static String getOrDefault(Function<String, String> env, String key, String defaultValue) {
        String value = env.apply(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static int parsePort(String portStr) {
        if (portStr == null || portStr.isBlank()) {
            return 8080;
        }
        try {
            int port = Integer.parseInt(portStr.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("PORT must be between 1 and 65535, got: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("PORT must be a valid integer, got: " + portStr);
        }
    }

    private static Map<String, String> parseJwtClaimMapping(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT_JWT_CLAIM_MAPPING;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("JWT_CLAIM_MAPPING must be valid JSON object, got: " + json, e);
        }
    }

    private static String getSystemHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
