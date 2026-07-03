package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.annotation.RequestScope;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.QueryAuthFetcher;
import edu.harvard.hms.dbmi.avillach.gateway.filter.BodyMutationFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.BufferingFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.PsamaIntrospectionFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.TokenRefreshResponseFilter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires the DB-free auth filter chain: {@code BufferingFilter}(10) -&gt; {@code OpenAccessFilter}(20) -&gt;
 * {@code PsamaIntrospectionFilter}(30) -&gt; {@code BodyMutationFilter}(35) -&gt; {@code TokenRefreshResponseFilter}(40) -&gt;
 * {@code IdentityPropagationFilter}(50). The audit filter (60) is wired in {@code AuditFilterConfig}. No datasource, no JPA --
 * {@link PsamaClient} and {@link QueryAuthFetcher} talk to PSAMA / the query service over HTTP only. <p> Spring Security itself stays
 * permit-all: the introspection filter above is the real auth boundary, matching the WAR's JWTFilter model rather than Spring Security's
 * authentication machinery.
 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

    // Auth-boundary HTTP clients (PSAMA introspection, query-service dispatch) run synchronously inside the request path; a
    // hung upstream must not tie up a Tomcat worker indefinitely, so both get bounded connect/read timeouts.
    static final Duration AUTH_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration AUTH_READ_TIMEOUT = Duration.ofSeconds(10);

    static final ClientHttpRequestFactorySettings AUTH_REQUEST_FACTORY_SETTINGS =
        ClientHttpRequestFactorySettings.defaults().withConnectTimeout(AUTH_CONNECT_TIMEOUT).withReadTimeout(AUTH_READ_TIMEOUT);

    private static RestClient.Builder timeoutBoundedRestClientBuilder() {
        return RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(AUTH_REQUEST_FACTORY_SETTINGS));
    }

    @Bean
    SecurityFilterChain http(HttpSecurity http) throws Exception {
        // Gateway permits all at the Security layer; the introspection filter is the real auth boundary.
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Per-request audit metadata holder. Request-scoped (rather than a shared singleton) so concurrent requests never leak metadata into
     * each other; singleton filters hold a TARGET_CLASS scoped proxy that resolves to the current request's instance.
     */
    @Bean
    @RequestScope
    AuditContext auditContext() {
        return new AuditContext();
    }

    @Bean
    GatewayAuthScope gatewayAuthScope(GatewaySecurityProperties props) {
        return new GatewayAuthScope(props.gatewayOwnsQueryReadAuth(), props.queryReadPaths());
    }

    @Bean
    PsamaClient psamaClient(GatewaySecurityProperties props) {
        return new PsamaClient(
            timeoutBoundedRestClientBuilder().build(), props.introspectionUrl(), props.openAccessValidateUrl(), props.serviceToken()
        );
    }

    @Bean
    QueryAuthFetcher queryAuthFetcher(GatewaySecurityProperties props) {
        // Phase 4: the dispatch endpoint (/internal/queries/{id}/dispatch) now lives on operations-service, the
        // sole DB owner -- not the DB-free query-service.
        return new QueryAuthFetcher(
            timeoutBoundedRestClientBuilder().build(), props.operationsServiceUrl(), props.queryServiceInternalToken()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "picsure.gateway.security", name = "auth-enabled", havingValue = "true")
    FilterRegistrationBean<BufferingFilter> bufferingFilter(
        GatewaySecurityProperties props, GatewayAuthScope scope, MeterRegistry meterRegistry
    ) {
        var r = new FilterRegistrationBean<>(new BufferingFilter(props.maxBodyBytes(), scope, meterRegistry));
        r.setOrder(10);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    @ConditionalOnProperty(prefix = "picsure.gateway.security", name = "auth-enabled", havingValue = "true")
    FilterRegistrationBean<OpenAccessFilter> openAccessFilter(
        PsamaClient client, AuditContext audit, ObjectMapper json, GatewayAuthScope scope, GatewaySecurityProperties props
    ) {
        var r = new FilterRegistrationBean<>(new OpenAccessFilter(client, audit, json, scope, props.openAccessEnabled()));
        r.setOrder(20);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    @ConditionalOnProperty(prefix = "picsure.gateway.security", name = "auth-enabled", havingValue = "true")
    FilterRegistrationBean<PsamaIntrospectionFilter> introspectionFilter(
        PsamaClient client, AuditContext audit, ObjectMapper json, QueryAuthFetcher fetcher, GatewayAuthScope scope,
        GatewaySecurityProperties props
    ) {
        var r = new FilterRegistrationBean<>(
            new PsamaIntrospectionFilter(client, audit, json, fetcher, scope, props.allowListPrefixes(), props.userIdClaim())
        );
        r.setOrder(30);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    @ConditionalOnProperty(prefix = "picsure.gateway.security", name = "auth-enabled", havingValue = "true")
    FilterRegistrationBean<BodyMutationFilter> bodyMutationFilter(ObjectMapper json) {
        var r = new FilterRegistrationBean<>(new BodyMutationFilter(json));
        r.setOrder(35);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    @ConditionalOnProperty(prefix = "picsure.gateway.security", name = "auth-enabled", havingValue = "true")
    FilterRegistrationBean<TokenRefreshResponseFilter> tokenRefreshFilter() {
        var r = new FilterRegistrationBean<>(new TokenRefreshResponseFilter());
        r.setOrder(40);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    @ConditionalOnProperty(prefix = "picsure.gateway.security", name = "auth-enabled", havingValue = "true")
    FilterRegistrationBean<IdentityPropagationFilter> identityFilter() {
        var r = new FilterRegistrationBean<>(new IdentityPropagationFilter());
        r.setOrder(50);
        r.addUrlPatterns("/*");
        return r;
    }
}
