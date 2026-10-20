package edu.harvard.hms.dbmi.avillach.query.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
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

    /**
     * Returns a {@link RestClient.Builder} for a client whose every wait is bounded by one call timeout, on a connection pool of its own
     * and with HttpComponents' automatic retries off, so a failed call is reported at once instead of being resent or held for a 503's
     * retry delay. The call timeout bounds the connect, the wait for the response to start, and each wait for more bytes of the response;
     * it does not bound the whole exchange, because HttpComponents' classic client has no total deadline, so a server that keeps sending
     * bytes more often than the timeout can keep a call open longer. Waiting for a pooled connection is bounded separately.
     *
     * @param maxTotal the pool's total connection limit
     * @param maxPerRoute the pool's per-route connection limit
     * @param callTimeout the connect, response and socket read timeout
     * @param leaseTimeout the longest wait for a free pooled connection
     * @return a builder backed by its own connection pool
     */
    static RestClient.Builder boundedBuilder(int maxTotal, int maxPerRoute, Duration callTimeout, Duration leaseTimeout) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxTotal);
        cm.setDefaultMaxPerRoute(maxPerRoute);
        cm.setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(Timeout.of(callTimeout)).setSocketTimeout(Timeout.of(callTimeout)).build()
        );
        RequestConfig requests = RequestConfig.custom().setConnectionRequestTimeout(Timeout.of(leaseTimeout))
            .setResponseTimeout(Timeout.of(callTimeout)).build();
        CloseableHttpClient httpClient =
            HttpClients.custom().setConnectionManager(cm).setDefaultRequestConfig(requests).disableAutomaticRetries().build();
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
