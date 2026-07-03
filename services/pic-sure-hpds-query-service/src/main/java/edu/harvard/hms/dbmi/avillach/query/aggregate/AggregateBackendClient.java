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
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;

/**
 * Pooled client to the open HPDS backend (+ visualization service) that the aggregate/obfuscation surface talks to. Direct port of
 * {@code AggregateDataSharingResourceRS}'s {@code postRequest}/{@code getHttpResponse} plumbing: every downstream call carries
 * {@code Authorization: Bearer <HPDS_OPEN_TOKEN>} (the WAR's {@code Bearer <getTargetPicsureToken()>} -- no regression), and every call
 * builds a fresh chained request body that carries the inbound query/credentials/resourceUUID but overrides the resourceUUID with the
 * configured {@code targetResourceId} when one is set (WAR's {@code createChainRequest}/info-request inline chaining).
 *
 * <p>Non-2xx responses and I/O failures surface as {@link HpdsCommunicationException} (mapped to 502 by
 * {@code edu.harvard.hms.dbmi.avillach.query.error.GlobalExceptionHandler}), mirroring {@code ResourceWebClient}'s error-handling
 * convention for the sibling HPDS client in this module.
 */
@Component
public class AggregateBackendClient {

    /** Mirrors {@code edu.harvard.dbmi.avillach.service.ResourceWebClient.QUERY_METADATA_FIELD} (the legacy WAR's header name). */
    public static final String QUERY_METADATA_FIELD = "resultMetadata";

    private final RestClient http;
    private final AggregateProperties props;

    public AggregateBackendClient(@Qualifier("aggregateRestClient") RestClient http, AggregateProperties props) {
        this.http = http;
        this.props = props;
    }

    public ResourceInfo info(QueryRequest req) {
        QueryRequest chained = chain(req);
        ResourceInfo info = postJson(openUrl("/info"), chained, ResourceInfo.class);
        // proxying info: return our own resource id when the caller supplied one (WAR parity)
        if (req != null && req.getResourceUUID() != null && info != null) {
            info.setId(req.getResourceUUID());
        }
        return info;
    }

    public SearchResults search(QueryRequest req) {
        return postJson(openUrl("/search"), chain(req), SearchResults.class);
    }

    public QueryStatus query(QueryRequest req) {
        return postJson(openUrl("/query"), chain(req), QueryStatus.class);
    }

    public QueryStatus status(String resourceQueryId, QueryRequest req) {
        return postJson(openUrl("/query/" + resourceQueryId + "/status"), chain(req), QueryStatus.class);
    }

    public String result(String resourceQueryId, QueryRequest req) {
        return postJson(openUrl("/query/" + resourceQueryId + "/result"), chain(req), String.class);
    }

    public String queryFormat(QueryRequest req) {
        return postJson(openUrl("/query/format"), chain(req), String.class);
    }

    /** Raw body + propagated resultMetadata header. The chained body carries the FULL request (resourceUUID injected). */
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
