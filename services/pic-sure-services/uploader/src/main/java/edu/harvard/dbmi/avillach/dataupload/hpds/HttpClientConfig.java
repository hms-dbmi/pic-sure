package edu.harvard.dbmi.avillach.dataupload.hpds;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient getHttpClient() {
        return HttpClient.newHttpClient();
    }
}
