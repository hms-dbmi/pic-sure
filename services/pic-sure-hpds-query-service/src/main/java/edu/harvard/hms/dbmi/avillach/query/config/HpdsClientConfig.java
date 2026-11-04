package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A single pooled {@link RestClient} using Apache HttpComponents 5 with 100 total and 20 per-route connections for talking to HPDS. It has
 * no base URL and no default Authorization header: {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector} resolves the target
 * backend's absolute base + service token, and the per-backend token is applied per call on query-lifecycle endpoints so search and values
 * calls stay token-free. Pool and timeout assembly is shared through {@link PooledRestClients}.
 */
@Configuration
@EnableConfigurationProperties(HpdsProperties.class)
public class HpdsClientConfig {

    @Bean
    RestClient hpdsClient(HpdsProperties props) {
        return PooledRestClients.pooledBuilder(100, 20, props.getConnectTimeoutSec(), props.getReadTimeoutSec()).build();
    }
}
