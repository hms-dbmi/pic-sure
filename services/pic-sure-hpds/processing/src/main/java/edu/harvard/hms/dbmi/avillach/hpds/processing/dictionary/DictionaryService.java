package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

import com.google.common.collect.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches concept details from the dictionary service's internal export metadata endpoint for exports that write a data dictionary table.
 * Every call carries the shared internal token. The bean always exists. A deployment that sets no {@code dictionary.url} or no
 * {@code picsure.internal.token} gets a {@link DictionaryUnavailableException} when an export asks for concepts.
 */
@Service
public class DictionaryService {

    public static final ParameterizedTypeReference<List<Concept>> CONCEPT_LIST_TYPE_REFERENCE = new ParameterizedTypeReference<>() {};
    private static final String URL_PROPERTY = "dictionary.url";
    private static final String TOKEN_PROPERTY = "picsure.internal.token";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "PICSURE_INTERNAL_TOKEN";
    private static final String EXPORT_METADATA_PATH = "/internal/concepts/export-metadata";
    private static final List<String> EXPORT_META_KEYS = List.of("drs_uri");

    /**
     * Largest number of concept paths the dictionary accepts in one export metadata request. Must match
     * {@code ConceptService.MAX_EXPORT_CONCEPT_PATHS} in the dictionary service, which this module does not depend on.
     */
    static final int MAX_BATCH_SIZE = 5000;

    /**
     * Header that carries the shared internal token. The value equals {@code InternalTokenFilter.HEADER} in pic-sure-spring-commons, which
     * this module does not depend on.
     */
    static final String INTERNAL_TOKEN_HEADER = "X-PIC-SURE-INTERNAL-TOKEN";

    private final String dictionaryUrl;
    private final String internalToken;
    private final int batchSize;
    private final RestTemplate restTemplate;

    /**
     * Creates the service with an HTTP client that applies the given timeouts.
     *
     * @param dictionaryUrl base URL of the dictionary service root, blank when the deployment has none
     * @param internalToken shared secret the dictionary requires on its internal endpoints, blank when the deployment has none
     * @param connectTimeoutMs milliseconds to wait for a connection to the dictionary
     * @param readTimeoutMs milliseconds to wait for the dictionary to answer one batch
     * @param batchSize largest number of concept paths sent in one request
     * @throws IllegalArgumentException if {@code batchSize} is below one
     */
    @Autowired
    public DictionaryService(
        @Value("${dictionary.url:}") String dictionaryUrl, @Value("${picsure.internal.token:}") String internalToken,
        @Value("${dictionary.connect-timeout-ms:5000}") int connectTimeoutMs,
        @Value("${dictionary.read-timeout-ms:30000}") int readTimeoutMs, @Value("${dictionary.batch-size:1000}") int batchSize
    ) {
        this(dictionaryUrl, internalToken, batchSize, restTemplateWithTimeouts(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * Creates the service around a supplied HTTP client.
     *
     * @param dictionaryUrl base URL of the dictionary service root, blank when the deployment has none
     * @param internalToken shared secret the dictionary requires on its internal endpoints, blank when the deployment has none
     * @param batchSize largest number of concept paths sent in one request
     * @param restTemplate client used for every dictionary call
     * @throws IllegalArgumentException if {@code batchSize} is below one
     */
    DictionaryService(String dictionaryUrl, String internalToken, int batchSize, RestTemplate restTemplate) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("dictionary.batch-size must be at least 1 but was " + batchSize);
        }
        this.dictionaryUrl = dictionaryUrl == null ? "" : dictionaryUrl.strip().replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken.strip();
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
     * dictionary does not know is left out of the result. Each concept's {@code meta} holds only {@code drs_uri}, under that raw key, when
     * the dictionary has one for it.
     *
     * @param conceptPaths the concept paths to look up
     * @return the concepts the dictionary returned across all batches
     * @throws DictionaryUnavailableException if the configured dictionary URL is blank or not an absolute http or https URL, the internal
     *         token is blank, the configured batch size is above {@value #MAX_BATCH_SIZE}, the dictionary rejects the token or the request,
     *         a request fails, or a response has no body
     */
    public List<Concept> getConcepts(List<String> conceptPaths) {
        String exportMetadataUrl = requireAbsoluteHttpUrl() + EXPORT_METADATA_PATH;
        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_TOKEN_HEADER, requireInternalToken());
        requireBatchSizeWithinDictionaryLimit();
        List<Concept> concepts = new ArrayList<>();
        for (List<String> batch : Lists.partition(conceptPaths, batchSize)) {
            concepts.addAll(fetchBatch(exportMetadataUrl, new HttpEntity<>(new ConceptExportRequest(batch, EXPORT_META_KEYS), headers)));
        }
        return concepts;
    }

    private void requireBatchSizeWithinDictionaryLimit() {
        if (batchSize > MAX_BATCH_SIZE) {
            throw new DictionaryUnavailableException(
                "dictionary.batch-size is " + batchSize + " but the dictionary accepts at most " + MAX_BATCH_SIZE
                    + " concept paths in one request, set dictionary.batch-size to " + MAX_BATCH_SIZE + " or less"
            );
        }
    }

    private String requireInternalToken() {
        if (internalToken.isEmpty()) {
            throw new DictionaryUnavailableException(
                "No internal token is configured for dictionary calls, set " + TOKEN_PROPERTY + " (" + TOKEN_ENVIRONMENT_VARIABLE + ")"
            );
        }
        return internalToken;
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

    private List<Concept> fetchBatch(String exportMetadataUrl, HttpEntity<ConceptExportRequest> request) {
        List<Concept> body;
        try {
            body = restTemplate.exchange(exportMetadataUrl, HttpMethod.POST, request, CONCEPT_LIST_TYPE_REFERENCE).getBody();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new DictionaryUnavailableException(
                "Dictionary request to " + exportMetadataUrl + " was rejected with " + e.getStatusCode().value() + ", check that "
                    + TOKEN_PROPERTY + " (" + TOKEN_ENVIRONMENT_VARIABLE + ") matches the token the dictionary is configured with",
                e
            );
        } catch (HttpClientErrorException e) {
            throw new DictionaryUnavailableException(
                "The dictionary rejected the request to " + exportMetadataUrl + " with status " + e.getStatusCode().value(), e
            );
        } catch (RestClientException e) {
            throw new DictionaryUnavailableException("Dictionary request to " + exportMetadataUrl + " failed", e);
        }
        if (body == null) {
            throw new DictionaryUnavailableException("Dictionary request to " + exportMetadataUrl + " returned no body");
        }
        return body;
    }
}
