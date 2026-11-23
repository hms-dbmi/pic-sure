package edu.harvard.hms.dbmi.avillach.operations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;
import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilterRegistration;

/**
 * Wires the operations service's {@link InternalTokenFilter} bean, whose secret comes from {@code picsure.operations.internal-token}. The
 * shared factory scopes it to {@code /internal/*}.
 */
@Configuration
public class InternalTokenFilterConfig {

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(@Value("${picsure.operations.internal-token:}") String secret) {
        return InternalTokenFilterRegistration.create(secret);
    }
}
