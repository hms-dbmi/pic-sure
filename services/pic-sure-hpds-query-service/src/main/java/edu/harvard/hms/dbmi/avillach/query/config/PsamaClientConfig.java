package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PsamaProperties.class)
public class PsamaClientConfig {

    @Bean
    RestClient psamaConsentRestClient(PsamaProperties properties) {
        return PooledRestClients.pooledBuilder(50, 20, properties.getConnectTimeoutSec(), properties.getReadTimeoutSec())
            .baseUrl(properties.getBaseUrl()).build();
    }
}
