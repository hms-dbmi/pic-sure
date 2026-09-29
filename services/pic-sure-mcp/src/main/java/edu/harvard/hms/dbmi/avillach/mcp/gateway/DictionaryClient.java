package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Calls the PIC-SURE data dictionary through the gateway. Every request sends an empty consents list, the same open view the public UI
 * gets, and replays the caller's headers. Failures surface as {@link org.springframework.web.client.RestClientException}s for the calling
 * tool to map.
 */
@Component
public class DictionaryClient {

    /** Gateway path of the concept search. */
    public static final String CONCEPTS_PATH = "/dictionary/concepts";

    /** Gateway path of the facet listing. */
    public static final String FACETS_PATH = "/dictionary/facets";

    /** Gateway path of the single-concept lookup, without the dataset. */
    public static final String DETAIL_PATH = "/dictionary/concepts/detail";

    private final RestClient restClient;

    /**
     * Creates the client.
     *
     * @param restClient the gateway client
     */
    public DictionaryClient(@Qualifier(GatewayClientConfig.GATEWAY_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Searches concepts by free text.
     *
     * @param search the free-text search
     * @param pageNumber the zero-based page
     * @param pageSize the page size
     * @param caller the caller's headers to replay
     * @return the page of concepts
     */
    public DictionaryPage searchConcepts(String search, int pageNumber, int pageSize, CallerHeaders caller) {
        return restClient.post().uri(CONCEPTS_PATH + "?page_number={p}&page_size={s}", pageNumber, pageSize).headers(caller::applyTo)
            .contentType(MediaType.APPLICATION_JSON).body(DictionaryFilter.open(search)).retrieve().body(DictionaryPage.class);
    }

    /**
     * Lists the facet categories that match a search.
     *
     * @param search the free-text search, possibly empty
     * @param caller the caller's headers to replay
     * @return the facet categories, empty when the dictionary returned none
     */
    public List<FacetCategory> listFacets(String search, CallerHeaders caller) {
        List<FacetCategory> categories = restClient.post().uri(FACETS_PATH).headers(caller::applyTo).contentType(MediaType.APPLICATION_JSON)
            .body(DictionaryFilter.open(search)).retrieve().body(new ParameterizedTypeReference<List<FacetCategory>>() {});
        return categories == null ? List.of() : categories;
    }

    /**
     * Looks up one concept.
     *
     * @param dataset the dataset the concept belongs to
     * @param conceptPath the raw concept path, sent as the request body
     * @param caller the caller's headers to replay
     * @return the concept
     */
    public DictionaryConcept conceptDetail(String dataset, String conceptPath, CallerHeaders caller) {
        return restClient.post().uri(DETAIL_PATH + "/{dataset}", dataset).headers(caller::applyTo).contentType(MediaType.TEXT_PLAIN)
            .body(conceptPath).retrieve().body(DictionaryConcept.class);
    }
}
