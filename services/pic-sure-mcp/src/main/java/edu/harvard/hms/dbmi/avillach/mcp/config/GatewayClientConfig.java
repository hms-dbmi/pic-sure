package edu.harvard.hms.dbmi.avillach.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the two outbound HTTP clients this service has, both {@link RestClient}s bound to {@code picsure.mcp.gateway-url}: the gateway
 * client, and one built the same way that waits longer for an answer, for authorized cross counts. Every tool reaches PIC-SURE data through
 * one of them. A caller's headers go on each call through {@link edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders#applyTo}, and
 * {@link GatewayRequestInterceptor} adds the MCP credential and refuses any target other than the gateway.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class GatewayClientConfig {

    /** Bean name of the gateway client. */
    public static final String GATEWAY_REST_CLIENT = "gatewayRestClient";

    /** Bean name of the gateway client for authorized cross counts, which waits {@link #CROSS_COUNT_READ_TIMEOUT} for an answer. */
    public static final String CROSS_COUNT_REST_CLIENT = "crossCountRestClient";

    /** How long to wait for a TCP connection to the gateway. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** How long to wait for the gateway to answer; an open count query can take tens of seconds. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /**
     * How long to wait for an authorized cross count, which can count up to 100 concept paths in one query: twice {@link #READ_TIMEOUT}.
     */
    public static final Duration CROSS_COUNT_READ_TIMEOUT = READ_TIMEOUT.multipliedBy(2);

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
     * @return the client every gateway call but an authorized cross count goes through
     */
    @Bean(GATEWAY_REST_CLIENT)
    public RestClient gatewayRestClient(RestClient.Builder builder, McpProperties properties) {
        return gatewayClient(builder, properties, REQUEST_FACTORY_SETTINGS);
    }

    /**
     * The gateway client for authorized cross counts: the same base URL, interceptor and redirect policy as the gateway client, and a read
     * timeout of {@link #CROSS_COUNT_READ_TIMEOUT}.
     *
     * @param builder Spring Boot's preconfigured builder, a fresh one per injection
     * @param properties the bound {@code picsure.mcp} properties
     * @return the client authorized cross counts go through
     */
    @Bean(CROSS_COUNT_REST_CLIENT)
    public RestClient crossCountRestClient(RestClient.Builder builder, McpProperties properties) {
        return gatewayClient(builder, properties, REQUEST_FACTORY_SETTINGS.withReadTimeout(CROSS_COUNT_READ_TIMEOUT));
    }

    private static RestClient gatewayClient(
        RestClient.Builder builder, McpProperties properties, ClientHttpRequestFactorySettings settings
    ) {
        return builder.baseUrl(properties.gatewayUrl()).requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .requestInterceptor(new GatewayRequestInterceptor(properties.gatewayUrl(), properties.serviceToken())).build();
    }
}
