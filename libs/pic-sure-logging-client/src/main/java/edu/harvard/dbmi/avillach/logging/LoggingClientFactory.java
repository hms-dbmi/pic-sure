package edu.harvard.dbmi.avillach.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that creates a {@link LoggingClient} from environment variables, returning {@link LoggingClient#noOp()} when the service is not
 * configured. <p> Expected environment variables: <ul> <li>{@code LOGGING_SERVICE_URL} — base URL of the logging service</li>
 * <li>{@code LOGGING_API_KEY} — API key for authentication</li> </ul> <p> Used by Spring Boot apps (HPDS, PSAMA, dictionary). For JAX-RS
 * apps on WildFly, use JNDI injection with {@link LoggingClientConfig#builder(String, String)} directly.
 */
public final class LoggingClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingClientFactory.class);

    private LoggingClientFactory() {}

    /**
     * Create a LoggingClient from environment variables. Returns {@link LoggingClient#noOp()} if the URL or API key is missing.
     *
     * @param clientType the default client_type for events (e.g. "api", "auth", "hpds")
     * @return a configured LoggingClient, or no-op if not configured
     */
    public static LoggingClient create(String clientType) {
        String url = System.getenv("LOGGING_SERVICE_URL");
        String key = System.getenv("LOGGING_API_KEY");

        if (url == null || url.trim().isEmpty() || key == null || key.trim().isEmpty()) {
            LOG.info("logging-client: LOGGING_SERVICE_URL or LOGGING_API_KEY not set; audit logging disabled");
            return LoggingClient.noOp();
        }

        LoggingClientConfig.Builder builder = LoggingClientConfig.builder(url, key);
        if (clientType != null && !clientType.trim().isEmpty()) {
            builder.clientType(clientType);
        }

        LOG.info("logging-client: configured for {} (clientType={})", url, clientType);
        return new LoggingClient(builder.build());
    }
}
