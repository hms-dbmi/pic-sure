package edu.harvard.dbmi.avillach.logging.config;

import edu.harvard.dbmi.avillach.logging.service.AuditLogService;
import edu.harvard.dbmi.avillach.logging.service.JwtDecodeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    public JwtDecodeService jwtDecodeService(LoggingProperties properties) {
        return new JwtDecodeService(properties.jwtClaimMapping());
    }

    @Bean
    public AuditLogService auditLogService(LoggingProperties properties, JwtDecodeService jwtDecodeService) {
        return new AuditLogService(properties, jwtDecodeService);
    }
}
