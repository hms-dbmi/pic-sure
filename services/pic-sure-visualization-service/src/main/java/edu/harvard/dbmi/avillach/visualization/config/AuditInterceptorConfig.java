package edu.harvard.dbmi.avillach.visualization.config;

import edu.harvard.dbmi.avillach.visualization.logging.AuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuditInterceptor}, which records each handler's audit label. The audit logging filter uses that label for the routes it
 * audits: {@code /auth/distributions}, {@code /open/distributions} and {@code /bin/continuous}.
 */
@Configuration
public class AuditInterceptorConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuditInterceptor());
    }
}
