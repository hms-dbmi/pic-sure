package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * The pooled {@link RestClient} backing {@link OperationsClient}. Unlike {@link HpdsClientConfig}'s HPDS client (which serves two backends
 * with different tokens and therefore carries no default Authorization header), this client talks to exactly one target --
 * operations-service -- so its base URL and the {@code X-PIC-SURE-INTERNAL-TOKEN} shared secret are set once as defaults and apply to every
 * call. Pool/timeout assembly is shared via {@link PooledRestClients}.
 */
@Configuration
@EnableConfigurationProperties(OperationsProperties.class)
public class OperationsClientConfig {

    @Bean
    RestClient operationsRestClient(OperationsProperties props) {
        return PooledRestClients.pooledBuilder(50, 20, props.getConnectTimeoutSec(), props.getReadTimeoutSec()).baseUrl(props.getBaseUrl())
            .defaultHeader("X-PIC-SURE-INTERNAL-TOKEN", props.getInternalToken()).build();
    }

    @Bean
    OperationsClient operationsClient(@Qualifier("operationsRestClient") RestClient operationsRestClient) {
        return new OperationsClient(operationsRestClient);
    }
}
