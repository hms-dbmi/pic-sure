package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

import com.google.common.collect.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches concept details from the dictionary service for exports that write a data dictionary table. The bean always exists. A deployment
 * that sets no {@code dictionary.url} gets a {@link DictionaryUnavailableException} when an export asks for concepts.
 */
@Service
public class DictionaryService {

    public static final ParameterizedTypeReference<List<Concept>> CONCEPT_LIST_TYPE_REFERENCE = new ParameterizedTypeReference<>() {};
    private static final String URL_PROPERTY = "dictionary.url";
    private static final String CONCEPT_DETAIL_PATH = "/concepts/detail";

    private final String dictionaryUrl;
    private final int batchSize;
    private final RestTemplate restTemplate;

    /**
     * Creates the service with an HTTP client that applies the given timeouts.
     *
     * @param dictionaryUrl base URL of the dictionary service root, blank when the deployment has none
     * @param connectTimeoutMs milliseconds to wait for a connection to the dictionary
     * @param readTimeoutMs milliseconds to wait for the dictionary to answer one batch
     * @param batchSize largest number of concept paths sent in one request
     * @throws IllegalArgumentException if {@code batchSize} is below one
     */
    @Autowired
    public DictionaryService(
        @Value("${dictionary.url:}") String dictionaryUrl, @Value("${dictionary.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${dictionary.read-timeout-ms:30000}") int readTimeoutMs, @Value("${dictionary.batch-size:1000}") int batchSize
    ) {
        this(dictionaryUrl, batchSize, restTemplateWithTimeouts(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * Creates the service around a supplied HTTP client.
     *
     * @param dictionaryUrl base URL of the dictionary service root, blank when the deployment has none
     * @param batchSize largest number of concept paths sent in one request
     * @param restTemplate client used for every dictionary call
     * @throws IllegalArgumentException if {@code batchSize} is below one
     */
    DictionaryService(String dictionaryUrl, int batchSize, RestTemplate restTemplate) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("dictionary.batch-size must be at least 1 but was " + batchSize);
        }
        this.dictionaryUrl = dictionaryUrl == null ? "" : dictionaryUrl.strip().replaceAll("/+$", "");
        this.batchSize = batchSize;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate restTemplateWithTimeouts(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new RestTemplate(requestFactory);
    }

    /**
     * Fetches the details of the given concept paths, one request per batch, in the order the dictionary returns them. A path the
     * dictionary does not know is left out of the result.
     *
     * @param conceptPaths the concept paths to look up
     * @return the concepts the dictionary returned across all batches
     * @throws DictionaryUnavailableException if the configured dictionary URL is blank or not an absolute http or https URL, a request
     *         fails, or a response has no body
     */
    public List<Concept> getConcepts(List<String> conceptPaths) {
        String conceptDetailUrl = requireAbsoluteHttpUrl() + CONCEPT_DETAIL_PATH;
        List<Concept> concepts = new ArrayList<>();
        for (List<String> batch : Lists.partition(conceptPaths, batchSize)) {
            concepts.addAll(fetchBatch(conceptDetailUrl, batch));
        }
        return concepts;
    }

    private String requireAbsoluteHttpUrl() {
        if (dictionaryUrl.isEmpty()) {
            throw new DictionaryUnavailableException("No dictionary URL is configured, set " + URL_PROPERTY);
        }
        URI uri;
        try {
            uri = new URI(dictionaryUrl);
        } catch (URISyntaxException e) {
            throw new DictionaryUnavailableException(URL_PROPERTY + " is not a valid URI: " + dictionaryUrl, e);
        }
        boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!httpScheme || uri.getHost() == null) {
            throw new DictionaryUnavailableException(URL_PROPERTY + " must be an absolute http or https URL but was: " + dictionaryUrl);
        }
        return dictionaryUrl;
    }

    private List<Concept> fetchBatch(String conceptDetailUrl, List<String> batch) {
        List<Concept> body;
        try {
            body = restTemplate.exchange(conceptDetailUrl, HttpMethod.POST, new HttpEntity<>(batch), CONCEPT_LIST_TYPE_REFERENCE).getBody();
        } catch (RestClientException e) {
            throw new DictionaryUnavailableException("Dictionary request to " + conceptDetailUrl + " failed", e);
        }
        if (body == null) {
            throw new DictionaryUnavailableException("Dictionary request to " + conceptDetailUrl + " returned no body");
        }
        return body;
    }
}
