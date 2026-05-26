package edu.harvard.dbmi.avillach.visualization.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Value("${rest-template.connect-timeout}")
    private int connectTimeout;

    @Value("${rest-template.read-timeout}")
    private int readTimeout;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.connectTimeout(Duration.ofMillis(connectTimeout)).readTimeout(Duration.ofMillis(readTimeout)).build();
    }
}
