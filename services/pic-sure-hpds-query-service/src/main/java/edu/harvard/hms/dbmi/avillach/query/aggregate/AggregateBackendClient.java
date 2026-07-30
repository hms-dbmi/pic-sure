package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.hms.dbmi.avillach.query.search.SearchResults;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;

/**
 * Pooled client to the open HPDS backend (+ visualization service) that the aggregate/obfuscation surface talks to. Port of
 * {@code AggregateDataSharingResourceRS}'s {@code postRequest}/{@code getHttpResponse} plumbing: every downstream call carries
 * {@code Authorization: Bearer <HPDS_OPEN_TOKEN>} (the WAR's {@code Bearer <getTargetPicsureToken()>} -- no regression).
 *
 * <p><b>Typed bodies, no chaining.</b> The WAR's {@code createChainRequest} rebuilt a {@code QueryRequest} envelope and overwrote its
 * {@code resourceUUID} with a configured target id; HPDS's v3 surface binds the bare {@link Query} and has no {@code resourceUUID} to
 * overwrite, so the envelope and both resource-id knobs are gone. {@link #search} posts the shared {@link SearchRequest} record to
 * {@code /v3/search} (the unversioned {@code /search} that took an envelope died with the v1 controller), {@link #querySync} posts the bare
 * {@link Query}, and {@link #binContinuous} posts the continuous counts alone.
 *
 * <p>Only the calls the obfuscation surface actually makes are exposed here: {@link #search} (the study-consents allow-list),
 * {@link #querySync} (the obfuscated sync path + the internal CROSS_COUNT lookup) and {@link #binContinuous} (visualization binning). The
 * async query-lifecycle calls ({@code /query}, {@code /query/{id}/status}, {@code /query/{id}/result}, {@code /query/format},
 * {@code /info}) are NOT proxied through this client: the open async submit is routed through {@code QueryService} (DB-free persistence +
 * dispatch, see {@link AggregateService#query}), and every subsequent read op is served by the v3 read ingress
 * ({@code /hpds/{backend}/v3/...}, {@link edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryV3Controller}) off the stored (already
 * consent-scoped) query.
 *
 * <p>Non-2xx responses and I/O failures surface as {@link HpdsCommunicationException} (mapped to 502 by
 * {@code edu.harvard.hms.dbmi.avillach.query.error.GlobalExceptionHandler}), mirroring {@code ResourceWebClient}'s error-handling
 * convention for the sibling HPDS client in this module.
 */
@Component
public class AggregateBackendClient {

    /**
     * The HPDS response header carrying the query/result metadata (e.g. the result id). Sourced from the module's own
     * {@link ResourceWebClient#QUERY_METADATA_FIELD} so the aggregate sync path reads AND re-emits the SAME header name
     * ({@code "queryMetadata"}) that the legacy WAR's {@code ResourceWebClient} and both aggregate resources
     * ({@code AggregateDataSharingResourceRS}/{@code RSV3}) used -- never a divergent literal that would silently drop the header.
     */
    public static final String QUERY_METADATA_FIELD = ResourceWebClient.QUERY_METADATA_FIELD;

    /** Every downstream HPDS/visualization call this client makes is a v3 one (the WAR v3 resource hardcoded the same prefix). */
    private static final String V3 = "/v3";

    private final RestClient http;
    private final AggregateProperties props;

    public AggregateBackendClient(@Qualifier("aggregateRestClient") RestClient http, AggregateProperties props) {
        this.http = http;
        this.props = props;
    }

    /** The study-consents lookup: a typed {@link SearchRequest} against HPDS's v3 search. */
    public SearchResults search(SearchRequest req) {
        String uri = openUrl("/search");
        try {
            return post(uri, req).retrieve().body(SearchResults.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("Aggregate search call failed: " + uri, e);
        }
    }

    /** Raw body + propagated queryMetadata header. The BARE v3 query is the whole request body. */
    public ResponseEntity<String> querySync(Query query) {
        String uri = openUrl("/query/sync");
        try {
            return post(uri, query).retrieve().toEntity(String.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("Aggregate query/sync call failed: " + uri, e);
        }
    }

    /**
     * Visualization {@code /v3/bin/continuous}. The body is that endpoint's {@code ContinuousBinningRequest} shape --
     * {@code {"continuousData": <continuous counts>}}; the field is NOT named {@code query} (it never was one, and the old name is now
     * rejected along with every other unmodelled property). The response is viz's {@code BinnedDistribution} wrapper, i.e. the bins live
     * under {@code bins} -- see {@link AggregateService#getBinnedContinuousCrossCount}, which unwraps it.
     *
     * <p>Written as a map literal rather than viz's record because that record is service-local: viz owns this contract, and query-service
     * is one client of it. The endpoint's strict deserialization is what keeps the two honest -- a drifted field name is a 400 here, not a
     * silently empty binning.
     */
    public String binContinuous(Map<String, Map<String, Integer>> continuousCounts) {
        String uri = props.getVisualizationUrl() + V3 + "/bin/continuous";
        try {
            return post(uri, Map.of("continuousData", continuousCounts)).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("Aggregate bin/continuous call failed: " + uri, e);
        }
    }

    // ---- internals ----

    /** The open HPDS backend's v3 base ({@code HPDS_OPEN_URL} already ends at the resource root, e.g. {@code .../PIC-SURE}). */
    private String openUrl(String path) {
        return props.getHpdsOpenUrl() + V3 + path;
    }

    private RestClient.RequestBodySpec post(String absoluteUrl, Object body) {
        return withAuth(http.post().uri(absoluteUrl).contentType(MediaType.APPLICATION_JSON)).body(body);
    }

    /** Inject the configured open-backend service token (HPDS_OPEN_TOKEN) when present. */
    private RestClient.RequestBodySpec withAuth(RestClient.RequestBodySpec spec) {
        String token = props.getHpdsOpenToken();
        if (token != null && !token.isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec;
    }
}
