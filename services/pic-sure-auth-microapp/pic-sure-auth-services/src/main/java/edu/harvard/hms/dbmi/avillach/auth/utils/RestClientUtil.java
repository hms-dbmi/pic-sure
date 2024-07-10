package edu.harvard.hms.dbmi.avillach.auth.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
public class RestClientUtil {

    private static final Logger logger = LoggerFactory.getLogger(RestClientUtil.class);
    private final RestTemplate restTemplate = new RestTemplate();

    public ResponseEntity<String> retrieveGetResponse(String uri, HttpHeaders headers) {
        try {
            HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
            // Pass custom configuration to the RestTemplate
            return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }

    // Implement: The ability to set the timeout on the rest template for a given request.
    public ResponseEntity<String> retrieveGetResponseWithRequestConfiguration(String uri, HttpHeaders headers, ClientHttpRequestFactory requestFactory) {
        if (requestFactory == null) {
            return retrieveGetResponse(uri, headers);
        }
        RestTemplate localRestTemplate = new RestTemplate(requestFactory);

        try {
            HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
            // Pass custom configuration to the RestTemplate
            return localRestTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }


    public ResponseEntity<String> retrievePostResponse(String uri, HttpHeaders headers, String body) {
        try {
            logger.debug("HttpClientUtilSpring retrievePostResponse()");
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            return restTemplate.postForEntity(uri, entity, String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }

    public ResponseEntity<String> retrievePostResponse(String uri, HttpEntity<MultiValueMap<String, String>> requestEntity) {
        try {
            return restTemplate.postForEntity(uri, requestEntity, String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }

    public static ClientHttpRequestFactory createRequestConfigWithCustomTimeout(int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return requestFactory;
    }

}
