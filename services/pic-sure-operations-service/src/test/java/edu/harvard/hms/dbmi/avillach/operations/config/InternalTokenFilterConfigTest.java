package edu.harvard.hms.dbmi.avillach.operations.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import edu.harvard.hms.dbmi.avillach.operations.query.InternalTokenFilter;

/**
 * Pure unit test for the {@link InternalTokenFilterConfig} bean method itself: proves the registration is scoped to the container-level
 * {@code /internal/*} URL pattern (not the default {@code /*}), which is the actual fix for path-gating that used to rely solely on
 * {@code InternalTokenFilter#shouldNotFilter} comparing the raw request URI.
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
