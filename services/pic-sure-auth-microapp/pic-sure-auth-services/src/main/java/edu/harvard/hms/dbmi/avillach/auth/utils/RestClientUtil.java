package edu.harvard.hms.dbmi.avillach.auth.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestClientUtil {

    private static final Logger logger = LoggerFactory.getLogger(RestClientUtil.class);
    private final RestTemplate restTemplate;

    @Autowired
    public RestClientUtil(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

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
    public ResponseEntity<String> retrieveGetResponseWithRequestConfiguration(String uri, HttpHeaders headers, int timeoutMs) {
        try {
            HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
            // Set timeout settings
            ((HttpComponentsClientHttpRequestFactory)restTemplate.getRequestFactory()).setConnectTimeout(timeoutMs);
            ((HttpComponentsClientHttpRequestFactory)restTemplate.getRequestFactory()).setConnectionRequestTimeout(timeoutMs);
            // Pass custom configuration to the RestTemplate
            return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }


    public ResponseEntity<String> retrievePostResponse(String uri, HttpHeaders headers, String body) throws HttpClientErrorException {
        logger.debug("HttpClientUtilSpring retrievePostResponse()");
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForEntity(uri, entity, String.class);
    }

    public ResponseEntity<String> retrievePostResponse(String uri, HttpEntity<MultiValueMap<String, String>> requestEntity) throws HttpClientErrorException {
        return restTemplate.postForEntity(uri, requestEntity, String.class);
    }
}
