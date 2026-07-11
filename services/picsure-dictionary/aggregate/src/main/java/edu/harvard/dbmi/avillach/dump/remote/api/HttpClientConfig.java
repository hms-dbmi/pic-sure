package edu.harvard.dbmi.avillach.dump.remote.api;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientConfig.class);

    @Value("${http.proxyUser:}")
    private String proxyUser;

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        CloseableHttpClient httpClient;
        if (!StringUtils.hasLength(proxyUser)) {
            LOG.info("No proxy user found, making default client.");
            httpClient =
                HttpClients.custom().setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(100).build())
                    .build();
        } else {
            LOG.info("Found proxy user {}, will configure proxy from system properties", proxyUser);
            httpClient = HttpClients.custom().useSystemProperties().build();
        }
        return builder.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
    }
}
