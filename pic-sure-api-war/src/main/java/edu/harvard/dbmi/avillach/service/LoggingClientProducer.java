package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientConfig;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.naming.InitialContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class LoggingClientProducer {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingClientProducer.class);

    private LoggingClient loggingClient;

    @Produces
    @ApplicationScoped
    public LoggingClient loggingClient() {
        String url = jndiLookup("java:global/logging_service_url");
        String key = jndiLookup("java:global/logging_api_key");

        if (url == null || url.trim().isEmpty() || key == null || key.trim().isEmpty()) {
            LOG.info("logging-client: JNDI bindings not set; audit logging disabled");
            loggingClient = LoggingClient.noOp();
        } else {
            LOG.info("logging-client: configured for {} (clientType=api)", url);
            loggingClient = new LoggingClient(
                LoggingClientConfig.builder(url, key).clientType("api").build()
            );
        }
        return loggingClient;
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (loggingClient != null) {
                loggingClient.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static String jndiLookup(String name) {
        try {
            return (String) new InitialContext().lookup(name);
        } catch (Exception e) {
            return null;
        }
    }
}
