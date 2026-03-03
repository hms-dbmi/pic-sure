package edu.harvard.hms.dbmi.avillach.resource.visualization;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientFactory;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

@ApplicationScoped
public class LoggingClientProducer {

    private final LoggingClient loggingClient = LoggingClientFactory.create("visualization");

    @Produces
    @ApplicationScoped
    public LoggingClient loggingClient() {
        return loggingClient;
    }

    @PreDestroy
    public void cleanup() {
        try {
            loggingClient.close();
        } catch (Exception ignored) {
        }
    }
}
