package edu.harvard.hms.dbmi.avillach.query.config;

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

    @Bean
    RestClient hpdsClient(HpdsProperties props) {
        return PooledRestClients.pooledBuilder(100, 20, props.getConnectTimeoutSec(), props.getReadTimeoutSec()).build();
    }

    /**
     * The Rust HPDS client: its own pool of 100 total and 20 per-route connections, so Rust calls never wait on Java's pool, with the read
     * timeout of {@code HPDS_RS_TIMEOUT_MS} and no automatic retries, so a failed Rust call falls back to Java at once instead of being
     * resent. Like {@link #hpdsClient} it has no base URL and no default Authorization header.
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
        return PooledRestClients
            .pooledBuilderWithReadTimeoutMillis(100, 20, props.getConnectTimeoutSec(), props.getRs().getTimeoutMs(), false).build();
    }
}
