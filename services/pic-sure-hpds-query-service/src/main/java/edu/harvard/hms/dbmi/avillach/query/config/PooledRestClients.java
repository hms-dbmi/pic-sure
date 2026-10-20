package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
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
        return pooledBuilderWithReadTimeoutMillis(
            maxTotal, maxPerRoute, connectTimeoutSec, TimeUnit.SECONDS.toMillis(readTimeoutSec), true
        );
    }

    /**
     * {@link #pooledBuilder} with the read timeout given in milliseconds, for a client whose timeout is configured at that resolution, and
     * with the choice to turn off HttpComponents' automatic retries, which resend a request after some I/O errors and wait out a 503.
     *
     * @param maxTotal the pool's total connection limit
     * @param maxPerRoute the pool's per-route connection limit
     * @param connectTimeoutSec the connect timeout in seconds
     * @param readTimeoutMillis the socket read timeout in milliseconds
     * @param automaticRetries whether HttpComponents retries on its own
     * @return a builder backed by its own connection pool
     */
    static RestClient.Builder pooledBuilderWithReadTimeoutMillis(
        int maxTotal, int maxPerRoute, int connectTimeoutSec, long readTimeoutMillis, boolean automaticRetries
    ) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxTotal);
        cm.setDefaultMaxPerRoute(maxPerRoute);
        cm.setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .setSocketTimeout((int) readTimeoutMillis, TimeUnit.MILLISECONDS).build()
        );
        HttpClientBuilder clientBuilder = HttpClients.custom().setConnectionManager(cm);
        if (!automaticRetries) {
            clientBuilder.disableAutomaticRetries();
        }
        CloseableHttpClient httpClient = clientBuilder.build();
        HttpComponentsClientHttpRequestFactory rf = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(rf);
    }
}
