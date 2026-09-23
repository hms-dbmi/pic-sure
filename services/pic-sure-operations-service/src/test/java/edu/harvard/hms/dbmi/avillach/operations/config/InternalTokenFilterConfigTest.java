package edu.harvard.hms.dbmi.avillach.operations.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import edu.harvard.hms.dbmi.avillach.operations.query.InternalTokenFilter;

/**
 * Verifies that the {@link InternalTokenFilterConfig} bean scopes registration to the container-level {@code /internal/*} URL pattern
 * rather than the default {@code /*}.
 */
class InternalTokenFilterConfigTest {

    @Test
    void registersTheFilterScopedToInternalUrlPatternOnly() {
        InternalTokenFilterConfig config = new InternalTokenFilterConfig();

        FilterRegistrationBean<InternalTokenFilter> registration = config.internalTokenFilter("secret");

        assertThat(registration.getUrlPatterns()).containsExactly("/internal/*");
        assertThat(registration.getFilter()).isInstanceOf(InternalTokenFilter.class);
    }
}
