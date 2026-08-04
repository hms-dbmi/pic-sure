package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A single pooled {@link RestClient} (Apache HttpComponents 5, maxTotal 100 / 20-per-route -- matching the legacy WAR's
 * {@code PoolingHttpClientConnectionManager}) for talking to HPDS. It has NO base URL and NO default Authorization header:
 * {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector} resolves the target backend's absolute base + service token, and the
 * per-backend token is applied per-call on the injecting endpoints (a later task) so that non-injecting search/values calls stay
 * token-free. Pool/timeout assembly is shared via {@link PooledRestClients}.
 */
@Configuration
@EnableConfigurationProperties(HpdsProperties.class)
public class HpdsClientConfig {

    @Bean
    RestClient hpdsClient(HpdsProperties props) {
        return PooledRestClients.pooledBuilder(100, 20, props.getConnectTimeoutSec(), props.getReadTimeoutSec()).build();
    }
}
