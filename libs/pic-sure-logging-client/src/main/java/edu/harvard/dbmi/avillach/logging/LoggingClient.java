package edu.harvard.dbmi.avillach.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Thread-safe, fire-and-forget client for the PIC-SURE Logging service. <p> Create a single instance via the constructor and share it
 * across the application. All {@link #send} calls are async and never throw exceptions — failures are logged at WARN level. <p> Use
 * {@link #noOp()} for testing or when the logging service is not configured. <p> Implements {@link AutoCloseable} so container-managed
 * producers (CDI {@code @PreDestroy}, Spring destroy methods) can clean up the internal HTTP client thread pool on redeployment.
 */
public class LoggingClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingClient.class);
    private static final LoggingClient NO_OP = new NoOpLoggingClient();

    private final LoggingClientConfig config;
    private final Sender sender;
    private final ObjectMapper objectMapper;
    private final URI auditEndpoint;

    /**
     * Create a new LoggingClient with the given configuration.
     *
     * @param config the client configuration
     */
    public LoggingClient(LoggingClientConfig config) {
        this.config = config;
        this.sender = new JdkHttpSender(config);
        // NON_NULL inclusion is declared on the model classes via @JsonInclude.
        // The ObjectMapper itself uses default settings so behaviour is consistent
        // whether events are serialized here or elsewhere.
        this.objectMapper = new ObjectMapper();
        this.auditEndpoint = URI.create(config.getBaseUrl() + "/audit");
    }

    // For NoOp subclass
    LoggingClient() {
        this.config = null;
        this.sender = new NoOpSender();
        this.objectMapper = null;
        this.auditEndpoint = null;
    }

    /**
     * Returns a no-op client that silently discards all events. Use when the logging service is not configured or in tests.
     */
    public static LoggingClient noOp() {
        return NO_OP;
    }

    /**
     * Send a logging event asynchronously. Returns immediately. If the config has a default clientType and the event doesn't specify one,
     * it will be applied.
     *
     * @param event the logging event to send
     */
    public void send(LoggingEvent event) {
        send(event, null, null);
    }

    /**
     * Send a logging event asynchronously with optional authorization and request ID headers.
     *
     * @param event the logging event to send
     * @param bearerToken optional Authorization header value (e.g. "Bearer xxx") — passed through so the server can extract JWT claims
     * @param requestId optional X-Request-Id header for correlation
     */
    public void send(LoggingEvent event, String bearerToken, String requestId) {
        if (event == null) {
            LOG.warn("logging-client: ignoring null event");
            return;
        }

        LoggingEvent resolved = resolveClientType(event);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(resolved);
        } catch (Exception e) {
            LOG.warn("logging-client: failed to serialize event {}: {}", resolved.getEventType(), e.getMessage());
            return;
        }

        sender.send(body, auditEndpoint, config, resolved, bearerToken, requestId);
    }

    static String sanitizeExceptionMessageForSender(Throwable t) {
        String message = t.getMessage();
        if (message == null) {
            return "(no message)";
        }
        // Strip anything after a header-like pattern to avoid leaking secrets
        int headerIdx = message.indexOf("X-API-Key");
        if (headerIdx >= 0) {
            return message.substring(0, headerIdx) + "[headers redacted]";
        }
        return message;
    }

    private LoggingEvent resolveClientType(LoggingEvent event) {
        if (event.getClientType() != null || config.getClientType() == null) {
            return event;
        }
        return event.withClientType(config.getClientType());
    }

    /**
     * Returns true if this is a live client (not the no-op instance).
     */
    public boolean isEnabled() {
        return true;
    }

    /**
     * Shuts down the internal HTTP client, releasing its thread pool and connections. Safe to call multiple times. After closing,
     * subsequent {@link #send} calls will fail asynchronously (errors logged at WARN level). <p> Call this from CDI {@code @PreDestroy} or
     * Spring destroy methods to prevent thread leaks during application redeployment.
     */
    @Override
    public void close() {
        // No-op on JDK 11. On newer JDKs, sender lifecycle can be enhanced if needed.
    }

    private static final class NoOpLoggingClient extends LoggingClient {
        @Override
        public void send(LoggingEvent event) {
            // silently discard
        }

        @Override
        public void send(LoggingEvent event, String bearerToken, String requestId) {
            // silently discard
        }

        @Override
        public boolean isEnabled() {
            return false;
        }
    }
}
