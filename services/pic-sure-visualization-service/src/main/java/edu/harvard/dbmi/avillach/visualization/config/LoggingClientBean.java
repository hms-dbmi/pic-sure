package edu.harvard.dbmi.avillach.visualization.config;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoggingClientBean {

    private static final Logger logger = LoggerFactory.getLogger(LoggingClientBean.class);

    @Bean
    public LoggingClient loggingClient(
        @Value("${logging.audit.url:}") String loggingUrl, @Value("${logging.audit.api-key:}") String apiKey
    ) {
        if (loggingUrl.isBlank() || apiKey.isBlank()) {
            logger.info("logging.audit.url or logging.audit.api-key not set; audit logging disabled");
            return LoggingClient.noOp();
        }

        LoggingClientConfig config = LoggingClientConfig.builder(loggingUrl, apiKey).clientType("visualization").build();
        logger.info("Audit logging enabled, sending to {}", loggingUrl);
        return new LoggingClient(config);
    }
}
