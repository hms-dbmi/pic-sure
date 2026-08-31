package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.annotation.RequestScope;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.filter.BufferingFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.PsamaIntrospectionFilter;
import edu.harvard.hms.dbmi.avillach.gateway.filter.TokenRefreshResponseFilter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires the DB-free auth filter chain: {@code BufferingFilter}(10) -&gt; {@code OpenAccessFilter}(20) -&gt;
 * {@code PsamaIntrospectionFilter}(30) -&gt; {@code TokenRefreshResponseFilter}(40) -&gt; {@code IdentityPropagationFilter}(50). The audit
 * filter (60) is wired in {@code AuditFilterConfig}. No datasource or JPA is used. {@link PsamaClient} talks to PSAMA over HTTP. <p> Spring
 * Security itself stays permit-all: the introspection filter above is the real auth boundary, matching the WAR's JWTFilter model rather
 * than Spring Security's authentication machinery.
 *
 * <p><b>Always registered:</b> all five filters are installed unconditionally. The shared {@link PublicEndpointPolicy} defines the
 * intentional public-route bypasses used by both authentication filters; all other routes traverse the normal auth chain.
 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

    // PSAMA introspection runs synchronously inside the request path, so bounded connect and read timeouts prevent a hung upstream from
    // tying up a Tomcat worker indefinitely.
    static final Duration AUTH_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration AUTH_READ_TIMEOUT = Duration.ofSeconds(10);

    static final ClientHttpRequestFactorySettings AUTH_REQUEST_FACTORY_SETTINGS =
        ClientHttpRequestFactorySettings.defaults().withConnectTimeout(AUTH_CONNECT_TIMEOUT).withReadTimeout(AUTH_READ_TIMEOUT);

    private static RestClient.Builder timeoutBoundedRestClientBuilder() {
        return RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(AUTH_REQUEST_FACTORY_SETTINGS));
    }

    @Bean
    @Order(10) // Yields /actuator/** to ActuatorSecurityConfig's @Order(0) chain.
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
    PsamaClient psamaClient(GatewaySecurityProperties props) {
        return new PsamaClient(
            timeoutBoundedRestClientBuilder().build(), props.introspectionUrl(), props.openAccessValidateUrl(), props.serviceToken()
        );
    }

    @Bean
    PublicEndpointPolicy publicEndpointPolicy(GatewaySecurityProperties props) {
        return new PublicEndpointPolicy(props.allowListPrefixes());
    }

    @Bean
    FilterRegistrationBean<BufferingFilter> bufferingFilter(GatewaySecurityProperties props, MeterRegistry meterRegistry) {
        var r = new FilterRegistrationBean<>(new BufferingFilter(props.maxBodyBytes(), meterRegistry));
        r.setOrder(10);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    FilterRegistrationBean<OpenAccessFilter> openAccessFilter(
        PsamaClient client, AuditContext audit, GatewaySecurityProperties props, PublicEndpointPolicy publicEndpoints
    ) {
        var r = new FilterRegistrationBean<>(new OpenAccessFilter(client, audit, props.openAccessEnabled(), publicEndpoints));
        r.setOrder(20);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    FilterRegistrationBean<PsamaIntrospectionFilter> introspectionFilter(
        PsamaClient client, AuditContext audit, PublicEndpointPolicy publicEndpoints
    ) {
        var r = new FilterRegistrationBean<>(new PsamaIntrospectionFilter(client, audit, publicEndpoints));
        r.setOrder(30);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    FilterRegistrationBean<TokenRefreshResponseFilter> tokenRefreshFilter() {
        var r = new FilterRegistrationBean<>(new TokenRefreshResponseFilter());
        r.setOrder(40);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    FilterRegistrationBean<IdentityPropagationFilter> identityFilter() {
        var r = new FilterRegistrationBean<>(new IdentityPropagationFilter());
        r.setOrder(50);
        r.addUrlPatterns("/*");
        return r;
    }
}
