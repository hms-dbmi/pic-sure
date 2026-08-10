package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Shared assembly for this module's pooled {@link RestClient}s (Apache HttpComponents 5). {@link HpdsClientConfig},
 * {@link AggregateHttpClientConfig} and {@link OperationsClientConfig} previously each duplicated the same
 * {@code PoolingHttpClientConnectionManager} + {@code ConnectionConfig} + request-factory wiring; only pool sizing, timeouts and per-client
 * defaults (base URL, default headers) differ, so the common part lives here and each config applies its own defaults to the returned
 * builder.
 *
 * <p>Future work: this builder is a candidate for {@code pic-sure-spring-commons} so other services (e.g. the gateway) can share it, but
 * that is a cross-module API decision deliberately not taken here.
 */
final class PooledRestClients {

    private PooledRestClients() {}

    /**
     * Returns a {@link RestClient.Builder} backed by a dedicated Apache HC5 connection pool with the given sizing and timeouts. No base URL
     * and no default headers are set -- callers add those when the client has a single fixed target.
     */
    static RestClient.Builder pooledBuilder(int maxTotal, int maxPerRoute, int connectTimeoutSec, int readTimeoutSec) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxTotal);
        cm.setDefaultMaxPerRoute(maxPerRoute);
        cm.setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .setSocketTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
        );
        CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(cm).build();
        HttpComponentsClientHttpRequestFactory rf = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(rf);
    }
}
