package edu.harvard.hms.dbmi.avillach.operations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.operations.query.InternalTokenFilter;

/**
 * Registers {@link InternalTokenFilter} exactly once, scoped at the container level to {@code /internal/*} via
 * {@link FilterRegistrationBean#addUrlPatterns(String...)}. Servlet URL-pattern matching is normalized and context-path-relative -- the
 * same routing the {@code DispatcherServlet} uses to reach {@code InternalQueryController} -- so this registration can never disagree with
 * "is this request actually going to the internal controller?" the way a hand-rolled {@code request.getRequestURI().startsWith(...)} check
 * could (e.g. under a non-empty {@code server.servlet.context-path}).
 *
 * <p>{@link InternalTokenFilter} is deliberately NOT a {@code @Component}: it is constructed here, directly, from the same
 * {@code picsure.operations.internal-token} property it always used. If it were also annotated {@code @Component}, Spring Boot's own
 * filter-bean auto-registration would additionally wire it in a second time (with the default {@code /*} mapping), which is exactly the
 * double-registration this class exists to avoid.
 */
@Configuration
public class InternalTokenFilterConfig {

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(
        @Value("${picsure.operations.internal-token:}") String expectedToken
    ) {
        FilterRegistrationBean<InternalTokenFilter> registration = new FilterRegistrationBean<>(new InternalTokenFilter(expectedToken));
        registration.addUrlPatterns("/internal/*");
        return registration;
    }
}
