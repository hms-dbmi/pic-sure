package edu.harvard.hms.dbmi.avillach.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the one outbound HTTP client this service has: a {@link RestClient} bound to {@code picsure.mcp.gateway-url}. Every tool reaches
 * PIC-SURE data through it. A caller's headers go on each call through
 * {@link edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders#applyTo}, and {@link GatewayRequestInterceptor} adds the MCP credential and
 * refuses any target other than the gateway.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class GatewayClientConfig {

    /** Bean name of the gateway client. */
    public static final String GATEWAY_REST_CLIENT = "gatewayRestClient";

    /** How long to wait for a TCP connection to the gateway. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** How long to wait for the gateway to answer; an open count query can take tens of seconds. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Request factory settings for the gateway client: the timeouts above, and redirects never followed, so a 3xx cannot carry the caller's
     * credentials or the MCP credential to another host.
     */
    public static final ClientHttpRequestFactorySettings REQUEST_FACTORY_SETTINGS =
        ClientHttpRequestFactorySettings.defaults().withConnectTimeout(CONNECT_TIMEOUT).withReadTimeout(READ_TIMEOUT)
            .withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW);

    /**
     * The gateway client.
     *
     * @param builder Spring Boot's preconfigured builder
     * @param properties the bound {@code picsure.mcp} properties
     * @return the client every gateway call goes through
     */
    @Bean(GATEWAY_REST_CLIENT)
    public RestClient gatewayRestClient(RestClient.Builder builder, McpProperties properties) {
        return builder.baseUrl(properties.gatewayUrl())
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(REQUEST_FACTORY_SETTINGS))
            .requestInterceptor(new GatewayRequestInterceptor(properties.gatewayUrl(), properties.serviceToken())).build();
    }
}
