package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A single pooled {@link RestClient} using Apache HttpComponents 5 with 100 total and 20 per-route connections for talking to the open HPDS
 * backend and the visualization service. It has no base URL and no default Authorization header: absolute per-request URLs and the
 * {@code Authorization: Bearer <HPDS_OPEN_TOKEN>} header are supplied per-call by {@code AggregateBackendClient}. The
 * {@link AggregateProperties} bean is registered by {@code AggregateConfig} via {@code @EnableConfigurationProperties} -- this class only
 * adds the HTTP client bean. Pool/timeout assembly is shared via {@link PooledRestClients}.
 */
@Configuration
public class AggregateHttpClientConfig {

    @Bean("aggregateRestClient")
    RestClient aggregateRestClient(AggregateProperties props) {
        // no base URL -- absolute per call
        return PooledRestClients.pooledBuilder(100, 20, props.getConnectTimeoutSec(), props.getReadTimeoutSec()).build();
    }
}
