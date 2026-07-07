package edu.harvard.dbmi.avillach.dataupload.hpds;

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
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;

@Configuration
public class HttpClientConfig {
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientConfig.class);

    @Value("${http.proxyUser:}")
    private String proxyUser;

    @Value("${http.proxyPassword:}")
    private String proxyPassword;

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        CloseableHttpClient httpClient;
        if (!StringUtils.hasLength(proxyUser)) {
            httpClient = HttpClients.createDefault();
        } else {
            LOG.info("Found proxy user {}, will configure proxy from system properties", proxyUser);
            httpClient =
                HttpClients.custom().setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(100).build())
                    .useSystemProperties().build();
        }
        return builder.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
    }

    @Bean
    public SdkHttpClient getSdkClient() {
        if (!StringUtils.hasLength(proxyUser)) {
            return null;
        }
        LOG.info("Found proxy user {}, will configure sdk proxy", proxyUser);
        ProxyConfiguration proxy =
            ProxyConfiguration.builder().useSystemPropertyValues(true).username(proxyUser).password(proxyPassword).build();
        return ApacheHttpClient.builder().proxyConfiguration(proxy).build();
    }
}
