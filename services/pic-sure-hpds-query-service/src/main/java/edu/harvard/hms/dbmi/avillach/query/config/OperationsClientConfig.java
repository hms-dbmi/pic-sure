package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * The pooled {@link RestClient} backing {@link OperationsClient}. Unlike {@link HpdsClientConfig}'s HPDS client (which serves two backends
 * with different tokens and therefore carries no default Authorization header), this client talks to exactly one target --
 * operations-service -- so its base URL and the {@code X-PIC-SURE-INTERNAL-TOKEN} shared secret are set once as defaults and apply to every
 * call.
 */
@Configuration
@EnableConfigurationProperties(OperationsProperties.class)
public class OperationsClientConfig {

    @Bean
    RestClient operationsRestClient(OperationsProperties props) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(50);
        cm.setDefaultMaxPerRoute(20);
        cm.setDefaultConnectionConfig(
            ConnectionConfig.custom().setConnectTimeout(props.getConnectTimeoutSec(), TimeUnit.SECONDS)
                .setSocketTimeout(props.getReadTimeoutSec(), TimeUnit.SECONDS).build()
        );
        CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(cm).build();
        HttpComponentsClientHttpRequestFactory rf = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().baseUrl(props.getBaseUrl()).defaultHeader("X-PIC-SURE-INTERNAL-TOKEN", props.getInternalToken())
            .requestFactory(rf).build();
    }

    @Bean
    OperationsClient operationsClient(@Qualifier("operationsRestClient") RestClient operationsRestClient) {
        return new OperationsClient(operationsRestClient);
    }
}
