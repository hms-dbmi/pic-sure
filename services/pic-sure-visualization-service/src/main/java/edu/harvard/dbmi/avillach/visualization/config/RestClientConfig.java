package edu.harvard.dbmi.avillach.visualization.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${rest-template.connect-timeout}")
    private int connectTimeout;

    @Value("${rest-template.read-timeout}")
    private int readTimeout;

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        // detect() resolves Apache HttpClient 5 from the classpath (the platform HTTP-client winner).
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(connectTimeout)).withReadTimeout(Duration.ofMillis(readTimeout));
        return builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
    }
}
