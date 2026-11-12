package edu.harvard.hms.dbmi.avillach.mcp.config;

import edu.harvard.hms.dbmi.avillach.mcp.caller.McpAccessFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts {@link McpAccessFilter} in front of the MCP endpoint only, so the actuator health probes stay open. */
@Configuration(proxyBeanMethods = false)
public class McpAccessConfig {

    /** Runs after the request-ID filter, so a refusal is logged under the caller's request ID. */
    static final int ORDER = 100;

    /**
     * Registers the access filter on {@code /mcp} and anything under it.
     *
     * @return the registration, so the guard covers the endpoint however the transport matches paths
     */
    @Bean
    public FilterRegistrationBean<McpAccessFilter> mcpAccessFilter() {
        FilterRegistrationBean<McpAccessFilter> registration = new FilterRegistrationBean<>(new McpAccessFilter());
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(ORDER);
        return registration;
    }
}
