package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;

/**
 * Pooled client to the open HPDS backend (+ visualization service) that the aggregate/obfuscation surface talks to. Direct port of
 * {@code AggregateDataSharingResourceRS}'s {@code postRequest}/{@code getHttpResponse} plumbing: every downstream call carries
 * {@code Authorization: Bearer <HPDS_OPEN_TOKEN>} (the WAR's {@code Bearer <getTargetPicsureToken()>} -- no regression), and every call
 * builds a fresh chained request body that carries the inbound query/credentials/resourceUUID but overrides the resourceUUID with the
 * configured {@code targetResourceId} when one is set (WAR's {@code createChainRequest}/info-request inline chaining).
 *
 * <p>Only the calls the obfuscation surface actually makes are exposed here: {@link #search} (used to fetch the study-consents allow-list),
 * {@link #querySync} (the obfuscated sync path + the internal CROSS_COUNT lookup) and {@link #binContinuous} (visualization binning). The
 * async query-lifecycle calls ({@code /query}, {@code /query/{id}/status}, {@code /query/{id}/result}, {@code /query/format},
 * {@code /info}) are NOT proxied through this client: the open async submit is routed through {@code QueryService} (DB-free persistence +
 * dispatch, see {@link AggregateService#query}), and every subsequent read op is served by the generic {@code /hpds/{backend}} controller
 * off the stored (already consent-scoped) query.
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

    private final RestClient http;
    private final AggregateProperties props;

    public AggregateBackendClient(@Qualifier("aggregateRestClient") RestClient http, AggregateProperties props) {
        this.http = http;
        this.props = props;
    }

    public SearchResults search(QueryRequest req) {
        return postJson(openUrl("/search"), chain(req), SearchResults.class);
    }

    /** Raw body + propagated queryMetadata header. The chained body carries the FULL request (resourceUUID injected). */
    public ResponseEntity<String> querySync(QueryRequest req, AggregateVariant variant) {
        String uri = openUrl(variant.downstreamVersionPrefix + "/query/sync");
        try {
            return post(uri, chain(req)).retrieve().toEntity(String.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("Aggregate query/sync call failed: " + uri, e);
        }
    }

    /** Visualization /bin/continuous (v3 prepends /v3). vizRequest already carries the viz resourceUUID. */
    public String binContinuous(QueryRequest vizRequest, AggregateVariant variant) {
        String uri = props.getVisualizationUrl() + variant.downstreamVersionPrefix + "/bin/continuous";
        try {
            return withAuth(http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)).body(vizRequest).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("Aggregate bin/continuous call failed: " + uri, e);
        }
    }

    // ---- internals ----

    /** Inject the configured resourceUUID (was target.resource.id) + carry inbound query/credentials. */
    private QueryRequest chain(QueryRequest in) {
        QueryRequest out = new GeneralQueryRequest();
        if (in != null) {
            out.setQuery(in.getQuery());
            out.setResourceCredentials(in.getResourceCredentials());
            out.setResourceUUID(in.getResourceUUID());
        }
        String targetId = props.getTargetResourceId();
        if (targetId != null && !targetId.isEmpty()) {
            out.setResourceUUID(UUID.fromString(targetId));
        }
        return out;
    }

    private String openUrl(String path) {
        return props.getHpdsOpenUrl() + path;
    }

    private RestClient.RequestBodySpec post(String absoluteUrl, QueryRequest body) {
        return withAuth(http.post().uri(absoluteUrl).contentType(MediaType.APPLICATION_JSON)).body(body);
    }

    private <T> T postJson(String uri, QueryRequest body, Class<T> type) {
        try {
            return post(uri, body).retrieve().body(type);
        } catch (RestClientException e) {
            throw new HpdsCommunicationException("Aggregate backend call failed: " + uri, e);
        }
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
