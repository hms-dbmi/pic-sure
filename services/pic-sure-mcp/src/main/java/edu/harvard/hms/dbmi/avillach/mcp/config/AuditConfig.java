package edu.harvard.hms.dbmi.avillach.mcp.config;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the audit event client the tool audit aspect sends through. */
@Configuration(proxyBeanMethods = false)
public class AuditConfig {

    /**
     * The logging client for this service, configured from {@code LOGGING_SERVICE_URL} and {@code LOGGING_API_KEY}. When either is unset
     * the factory returns the no-op client, so every audit event is discarded and no tool call is affected.
     *
     * @return the client, closed at shutdown
     */
    @Bean(destroyMethod = "close")
    public LoggingClient mcpLoggingClient() {
        return LoggingClientFactory.create("mcp");
    }
}
