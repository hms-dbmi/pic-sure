package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A single pooled {@link RestClient} (Apache HttpComponents 5, maxTotal 100 / 20-per-route -- matching the legacy WAR's
 * {@code PoolingHttpClientConnectionManager}, see {@code AggregateHttpClientConfig}'s counterpart {@link HpdsClientConfig}) for talking to
 * the open HPDS backend and the visualization service. It has NO base URL and NO default Authorization header: absolute per-request URLs
 * and the {@code Authorization: Bearer <HPDS_OPEN_TOKEN>} header are supplied per-call by {@code AggregateBackendClient}. The
 * {@link AggregateProperties} bean itself is already registered by {@code AggregateConfig} (Unit 5a) via
 * {@code @EnableConfigurationProperties} -- this class only adds the HTTP client bean.
 */
@Configuration
public class AggregateHttpClientConfig {

    @Bean("aggregateRestClient")
    RestClient aggregateRestClient(AggregateProperties props) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(100); // matches the WAR's PoolingHttpClientConnectionManager
        cm.setDefaultMaxPerRoute(20);
        cm.setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(props.getConnectTimeoutSec(), TimeUnit.SECONDS)
                .setSocketTimeout(props.getReadTimeoutSec(), TimeUnit.SECONDS).build()
        );
        CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(cm).build();
        HttpComponentsClientHttpRequestFactory rf = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(rf).build(); // no base URL -- absolute per call
    }
}
