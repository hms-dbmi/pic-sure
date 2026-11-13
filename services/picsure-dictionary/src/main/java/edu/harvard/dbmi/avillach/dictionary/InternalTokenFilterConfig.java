package edu.harvard.dbmi.avillach.dictionary;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;
import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilterRegistration;

/**
 * Registers {@link InternalTokenFilter} exactly once for this service, scoped to {@code /internal/*}, with the inbound secret read from
 * {@code picsure.internal.token}. Whitespace around the configured value is removed, as HPDS removes it from the value it sends, so a
 * trailing line ending in one service's environment cannot make two identical secrets disagree. Every other endpoint of the dictionary
 * stays outside the filter and is served as before.
 */
@Configuration
public class InternalTokenFilterConfig {

    /**
     * Creates the token gate for the internal endpoints.
     *
     * @param token the configured shared secret, where a value that is blank once stripped rejects every internal request
     * @return a registration that maps the filter to {@code /internal/*} only
     */
    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(@Value("${picsure.internal.token:}") String token) {
        return InternalTokenFilterRegistration.create(token == null ? null : token.strip());
    }
}
