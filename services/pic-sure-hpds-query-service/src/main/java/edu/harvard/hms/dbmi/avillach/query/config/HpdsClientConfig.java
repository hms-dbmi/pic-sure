package edu.harvard.hms.dbmi.avillach.query.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The pooled {@link RestClient} using Apache HttpComponents 5 with 100 total and 20 per-route connections for talking to HPDS. It has no
 * base URL and no default Authorization header: {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector} resolves the target
 * backend's absolute base + service token, and the per-backend token is applied per call on query-lifecycle endpoints so search and values
 * calls stay token-free. Pool and timeout assembly is shared through {@link PooledRestClients}. The Rust HPDS backend has a second pooled
 * client of its own, {@link #hpdsRsClient}.
 */
@Configuration
@EnableConfigurationProperties(HpdsProperties.class)
public class HpdsClientConfig {

    /** The longest a Rust call waits for a free pooled connection, or {@code HPDS_RS_TIMEOUT_MS} when that is shorter. */
    public static final Duration RUST_LEASE_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    RestClient hpdsClient(HpdsProperties props) {
        return PooledRestClients.pooledBuilder(100, 20, props.getConnectTimeoutSec(), props.getReadTimeoutSec()).build();
    }

    /**
     * The Rust HPDS client: its own pool of 100 total and 20 per-route connections, so Rust calls never wait on Java's pool. Every wait is
     * bounded by {@code HPDS_RS_TIMEOUT_MS} (the connect, the start of the response, and each wait for more of it) and a wait for a free
     * pooled connection by {@link #RUST_LEASE_TIMEOUT}, and nothing is retried, so a failed Rust call falls back to Java at once. Like
     * {@link #hpdsClient} it has no base URL and no default Authorization header.
     *
     * @param props the HPDS settings
     * @return the pooled client for Rust HPDS calls
     */
    @Bean
    RestClient hpdsRsClient(HpdsProperties props) {
        return rustClientFor(props);
    }

    /**
     * Builds the Rust HPDS client described on {@link #hpdsRsClient}, outside a Spring context.
     *
     * @param props the HPDS settings
     * @return a new pooled client
     */
    public static RestClient rustClientFor(HpdsProperties props) {
        return rustClientFor(props, 100, 20);
    }

    /**
     * Builds the Rust HPDS client with the given pool sizes.
     *
     * @param props the HPDS settings
     * @param maxTotal the pool's total connection limit
     * @param maxPerRoute the pool's per-route connection limit
     * @return a new pooled client
     */
    public static RestClient rustClientFor(HpdsProperties props, int maxTotal, int maxPerRoute) {
        Duration callTimeout = Duration.ofMillis(props.getRs().getTimeoutMs());
        Duration lease = callTimeout.compareTo(RUST_LEASE_TIMEOUT) < 0 ? callTimeout : RUST_LEASE_TIMEOUT;
        return PooledRestClients.boundedBuilder(maxTotal, maxPerRoute, callTimeout, lease).build();
    }
}
