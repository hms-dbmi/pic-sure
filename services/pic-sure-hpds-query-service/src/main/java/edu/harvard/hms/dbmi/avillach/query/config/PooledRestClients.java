package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Shared assembly for this module's pooled Apache HttpComponents 5 {@link RestClient}s. It provides connection-manager, timeout, and
 * request-factory wiring; each client configuration supplies its own pool sizing and defaults.
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
