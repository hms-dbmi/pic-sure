package edu.harvard.dbmi.avillach.dataupload.hpds;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
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
    public HttpClient getHttpClient() {
        if (!StringUtils.hasLength(proxyUser)) {
            return HttpClients.createDefault();
        }
        LOG.info("Found proxy user {}, will configure proxy", proxyUser);
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(100);
        return HttpClients
            .custom()
            .setConnectionManager(new PoolingHttpClientConnectionManager())
            .useSystemProperties()
            .build();
    }


    @Bean
    public SdkHttpClient getSdkClient() {
        if (!StringUtils.hasLength(proxyUser)) {
            return null;
        }
        LOG.info("Found proxy user {}, will configure sdk proxy", proxyUser);
        ProxyConfiguration proxy = ProxyConfiguration.builder()
            .useSystemPropertyValues(true)
            .username(proxyUser)
            .password(proxyPassword)
            .build();
        return ApacheHttpClient.builder()
            .proxyConfiguration(proxy)
            .build();
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
