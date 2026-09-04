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
 * Pooled client for the open HPDS backend and visualization service used by the aggregate and obfuscation surface. Every downstream call
 * carries {@code Authorization: Bearer <HPDS_OPEN_TOKEN>} and builds a fresh request body containing the inbound query, credentials, and
 * resource UUID. A configured {@code targetResourceId} overrides the inbound resource UUID.
 *
 * <p>Only the calls the obfuscation surface actually makes are exposed here: {@link #search} (used to fetch the study-consents allow-list),
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
     * The HPDS response header carrying query and result metadata, such as the result id. Referencing
     * {@link ResourceWebClient#QUERY_METADATA_FIELD} ensures the aggregate sync path reads and re-emits the same header name.
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

    /** Carries the inbound query */
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
