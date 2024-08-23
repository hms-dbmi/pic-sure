package edu.harvard.hms.dbmi.avillach.auth.utils;


import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RestClientConfig.class);

    @Value("${http.proxyHost:}")
    private String proxyHost;

    @Value("${http.proxyPort:}")
    private int proxyPort;

    @Value("${http.proxyUser:}")
    private String proxyUser;

    @Value("${http.proxyPassword:}")
    private String proxyPassword;

    @Bean
    public HttpClient getHttpClient() {
        if (!StringUtils.hasLength(proxyHost)) {
            return HttpClients.createDefault();
        } else if (!StringUtils.hasLength(proxyUser)) {
            LOG.info("Utilizing unauthenticated proxy: host={}", proxyHost);
            return HttpClients.custom()
                .setProxy(new HttpHost(proxyHost, proxyPort))
                .build();
        } else {
            LOG.info("Utilizing authenticated proxy: host={}, user={}", proxyHost, proxyUser);

            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                new AuthScope(proxyHost, proxyPort),
                new UsernamePasswordCredentials(proxyUser, proxyPassword.toCharArray()));

            return HttpClients.custom()
                .setDefaultCredentialsProvider(credentialsProvider)
                .setProxy(new HttpHost(proxyHost, proxyPort))
                .build();
        }
    }

    @Bean
    public RestTemplate getRestTemplate(@Autowired HttpClient client) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setHttpClient(client);
        return new RestTemplate(factory);
    }
}
