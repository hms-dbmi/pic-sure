package edu.harvard.dbmi.avillach.dump;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LoggingConfig implements WebMvcConfigurer {

    @Value("${DEST_IP:#{null}}")
    private String destIp;

    @Value("${DEST_PORT:#{null}}")
    private Integer destPort;

    @Bean
    public LoggingClient loggingClient() {
        return LoggingClientFactory.create("dump");
    }

    @Bean
    public AuditLoggingFilter auditLoggingFilter(LoggingClient loggingClient) {
        return new AuditLoggingFilter(loggingClient, destIp, destPort);
    }

    @Bean
    public FilterRegistrationBean<AuditLoggingFilter> auditLoggingFilterRegistration(AuditLoggingFilter auditLoggingFilter) {
        FilterRegistrationBean<AuditLoggingFilter> registration = new FilterRegistrationBean<>(auditLoggingFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public AuditInterceptor auditInterceptor() {
        return new AuditInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditInterceptor());
    }
}
