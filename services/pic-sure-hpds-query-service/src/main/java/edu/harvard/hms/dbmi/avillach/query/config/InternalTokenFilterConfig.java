package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;
import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilterRegistration;

/**
 * Registers {@link InternalTokenFilter} exactly once for this service, scoped to {@code /internal/*}, with the inbound secret read from
 * {@code picsure.internal.token}. This token is separate from {@link OperationsProperties#getInternalToken()}, which this service sends
 * outbound to the operations service.
 */
@Configuration
public class InternalTokenFilterConfig {

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(@Value("${picsure.internal.token:}") String token) {
        return InternalTokenFilterRegistration.create(token);
    }
}
