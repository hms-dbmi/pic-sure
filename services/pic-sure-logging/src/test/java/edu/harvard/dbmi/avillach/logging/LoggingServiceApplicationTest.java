package edu.harvard.dbmi.avillach.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.config.JwtClaimMappingConverter;
import edu.harvard.dbmi.avillach.logging.config.LoggingProperties;
import edu.harvard.dbmi.avillach.logging.service.AuditAppenderFailureMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "picsure.logging.api-key=test-key")
class LoggingServiceApplicationTest {

    @Autowired
    private LoggingProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditAppenderFailureMonitor auditAppenderFailureMonitor;

    @Test
    void contextLoadsAndBindsProperties() {
        assertThat(properties.apiKey()).isEqualTo("test-key");
        assertThat(properties.app()).isEqualTo("unknown");
        assertThat(properties.allowedOrigin()).isEqualTo("*");
    }

    @Test
    void blankJwtClaimMappingEnvBindsToTheDefaultMap() {
        // application.yml supplies "" via ${JWT_CLAIM_MAPPING:}; the converter turns it
        // into the default map. This proves the @ConfigurationPropertiesBinding wiring works.
        assertThat(properties.jwtClaimMapping()).isEqualTo(JwtClaimMappingConverter.DEFAULT_MAPPING);
    }

    @Test
    void contextUsesBootsAutoConfiguredObjectMapper() throws Exception {
        // JavaTimeModule is registered only by Boot's JacksonAutoConfiguration. If a
        // hand-built ObjectMapper bean suppresses it, this throws InvalidDefinitionException
        // ("Java 8 date/time type ... not supported").
        assertThat(objectMapper.writeValueAsString(Instant.EPOCH)).contains("1970-01-01");
    }

    @Test
    void contextRegistersAuditAppenderFailureMonitor() {
        assertThat(auditAppenderFailureMonitor).isNotNull();
    }
}
