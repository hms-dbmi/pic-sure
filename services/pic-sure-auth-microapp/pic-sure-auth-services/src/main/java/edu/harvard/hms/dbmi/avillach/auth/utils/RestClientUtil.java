package edu.harvard.hms.dbmi.avillach.auth.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

@Component
public class RestClientUtil {

    private static final Logger logger = LoggerFactory.getLogger(RestClientUtil.class);
    private static final RestTemplate restTemplate = new RestTemplate();

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

    public String composeURL(String baseURL, String pathName, String query) {
        try {
            URI uri = new URI(baseURL);
            List<String> basePathComponents = Arrays.asList(uri.getPath().split("/"));
            List<String> pathNameComponents = Arrays.asList(pathName.split("/"));
            List<String> allPathComponents = new LinkedList<>();
            Predicate<? super String> nonEmpty = segment -> !segment.isEmpty();
            allPathComponents.addAll(basePathComponents.stream().filter(nonEmpty).toList());
            allPathComponents.addAll(pathNameComponents.stream().filter(nonEmpty).toList());
            String queryString = query == null ? uri.getQuery() : query;
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), "/" + String.join("/", allPathComponents), queryString, uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException("baseURL invalid : " + baseURL, e);
        }
    }

    public ResponseEntity<String> retrievePostResponse(String uri, HttpHeaders headers, String body) {
        try {
            logger.debug("HttpClientUtilSpring retrievePostResponse()");
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            return restTemplate.postForEntity(uri, entity, String.class);
        } catch (HttpClientErrorException ex) {
            logger.error("HttpClientErrorException: {}", ex.getMessage());
            throw ex;
        }
    }

}
