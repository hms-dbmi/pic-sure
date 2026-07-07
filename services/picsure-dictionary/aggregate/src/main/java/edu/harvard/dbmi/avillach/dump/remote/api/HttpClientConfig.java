package edu.harvard.dbmi.avillach.dump.remote.api;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class HttpClientConfig {
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientConfig.class);

    @Value("${http.proxyUser:}")
    private String proxyUser;

    @Value("${http.proxyPassword:}")
    private String proxyPassword;

    @Bean
    public CloseableHttpClient getHttpClient() {
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(100);
        if (!StringUtils.hasLength(proxyUser)) {
            LOG.info("No proxy user found, making default client.");
            return HttpClients.custom().setConnectionManager(manager).build();
        }
        RequestConfig config = RequestConfig.custom().setConnectionRequestTimeout(0) // No timeout for getting a connection from pool
            .setConnectTimeout(0) // No timeout for establishing connection
            .setSocketTimeout(0) // No timeout between packets
            .build();

        HttpClients.custom().setDefaultRequestConfig(config).build();
        LOG.info("Found proxy user {}, will configure proxy", proxyUser);

        return HttpClients.custom().setDefaultRequestConfig(config).setDefaultRequestConfig(config)
            .setConnectionManager(new PoolingHttpClientConnectionManager()).useSystemProperties().build();
    }


    @Bean
    public HttpClientContext getClientConfig() {
        if (StringUtils.hasLength(proxyUser) && StringUtils.hasLength(proxyPassword)) {
            HttpClientContext httpClientContext = HttpClientContext.create();
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(proxyUser, proxyPassword));
            httpClientContext.setCredentialsProvider(credentialsProvider);

            return httpClientContext;
        }
        return HttpClientContext.create();
    }
}
