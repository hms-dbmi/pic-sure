package edu.harvard.hms.dbmi.avillach.auth.utils;

import org.apache.hc.client5.http.classic.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class RestClientUtil {

    private static final Logger logger = LoggerFactory.getLogger(RestClientUtil.class);
    private final RestClient restClient;
    private final HttpClient httpClient;

    @Autowired
    public RestClientUtil(RestClient restClient, HttpClient httpClient) {
        this.restClient = restClient;
        this.httpClient = httpClient;
    }

    public ResponseEntity<String> retrieveGetResponse(String uri, HttpHeaders headers) {
        try {
            return restClient.get().uri(uri).headers(h -> h.addAll(headers)).retrieve().toEntity(String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }

    // The ability to set the timeout for a given request: a one-off client with a
    // dedicated request factory, so the shared client's settings are never mutated.
    public ResponseEntity<String> retrieveGetResponseWithRequestConfiguration(String uri, HttpHeaders headers, int timeoutMs) {
        try {
            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(timeoutMs);
            factory.setConnectionRequestTimeout(timeoutMs);
            RestClient timeoutBoundClient = RestClient.builder().requestFactory(factory).build();
            return timeoutBoundClient.get().uri(uri).headers(h -> h.addAll(headers)).retrieve().toEntity(String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }


    public ResponseEntity<String> retrievePostResponse(String uri, HttpHeaders headers, String body) throws HttpClientErrorException {
        logger.debug("HttpClientUtilSpring retrievePostResponse()");
        return restClient.post().uri(uri).headers(h -> h.addAll(headers)).body(body).retrieve().toEntity(String.class);
    }

    public ResponseEntity<String> retrievePostResponse(String uri, HttpEntity<MultiValueMap<String, String>> requestEntity)
        throws HttpClientErrorException {
        return restClient.post().uri(uri).headers(h -> h.addAll(requestEntity.getHeaders())).body(requestEntity.getBody()).retrieve()
            .toEntity(String.class);
    }
}
