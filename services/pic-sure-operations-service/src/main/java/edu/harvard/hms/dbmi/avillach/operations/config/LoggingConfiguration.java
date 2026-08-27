package edu.harvard.hms.dbmi.avillach.operations.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientFactory;

@Configuration
public class LoggingConfiguration {

    @Bean(destroyMethod = "close")
    LoggingClient operationsLoggingClient() {
        return LoggingClientFactory.create("operations");
    }
}
