package edu.harvard.dbmi.avillach.logging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

    public LoggingProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LOGGING_API_KEY environment variable is required");
        }
        app = defaultIfBlank(app, "unknown");
        platform = defaultIfBlank(platform, "unknown");
        environment = defaultIfBlank(environment, "unknown");
        hostname = defaultIfBlank(hostname, systemHostname());
        allowedOrigin = defaultIfBlank(allowedOrigin, "*");
        jwtClaimMapping = (jwtClaimMapping == null) ? JwtClaimMappingConverter.DEFAULT_MAPPING
            : Collections.unmodifiableMap(new LinkedHashMap<>(jwtClaimMapping));
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
