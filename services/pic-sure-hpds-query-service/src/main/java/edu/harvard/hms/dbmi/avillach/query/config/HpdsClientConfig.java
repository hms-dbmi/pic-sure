package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A single pooled {@link RestClient} (Apache HttpComponents 5, maxTotal 100 / 20-per-route -- matching the legacy WAR's
 * {@code PoolingHttpClientConnectionManager}) for talking to HPDS. It has NO base URL and NO default Authorization header:
 * {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector} resolves the target backend's absolute base + service token, and the
 * per-backend token is applied per-call on the injecting endpoints (a later task) so that non-injecting search/values calls stay
 * token-free.
 */
@Configuration
@EnableConfigurationProperties(HpdsProperties.class)
public class HpdsClientConfig {

    @Bean
    RestClient hpdsClient(HpdsProperties props) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(100);
        cm.setDefaultMaxPerRoute(20);
        cm.setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(props.getConnectTimeoutSec(), TimeUnit.SECONDS)
                .setSocketTimeout(props.getReadTimeoutSec(), TimeUnit.SECONDS).build()
        );
        CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(cm).build();
        HttpComponentsClientHttpRequestFactory rf = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(rf).build();
    }
}
