package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.DistributionType;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Posts distribution sub-queries to {@code pic-sure-hpds-query-service}, the single HPDS ingress. This service does NOT talk to HPDS
 * directly -- query-service owns that hop, selects the backend from the {@code /hpds/{auth,open}} path segment, and applies the open path's
 * threshold/variance obfuscation.
 *
 * <p>The gateway's {@code X-User-*} headers are forwarded verbatim because query-service gates {@code /hpds/**} behind
 * {@code .authenticated()}, which its {@code GatewayPrivilegesFilter} satisfies only when {@code X-User-Id} is present. Open-access
 * requests carry the marker {@code OPEN_ACCESS:<host>} in that header, which satisfies the rule. The visualization request path selects the
 * auth or open backend.
 *
 * <p>No {@code resourceUUID} is sent. query-service selects its backend from the path and only echoes the field back, so including it would
 * imply a routing role it no longer has.
 */
@Component
public class QueryServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(QueryServiceClient.class);

    /** Gateway-owned identity headers and the caller credential to forward downstream. */
    public record GatewayIdentity(
        String userId, String subject, String email, String roles, String privileges, String authorizationHeader
    ) {

        public GatewayIdentity(String userId, String subject, String email, String roles, String privileges) {
            this(userId, subject, email, roles, privileges, null);
        }
    }

    private final RestClient restClient;
    private final LoggingClient loggingClient;
    private final String queryServiceBaseUrl;

    public QueryServiceClient(
        RestClient restClient, LoggingClient loggingClient, @Value("${picsure.query-service.base-url}") String queryServiceBaseUrl
    ) {
        this.restClient = restClient;
        this.loggingClient = loggingClient;
        this.queryServiceBaseUrl = queryServiceBaseUrl;
    }

    public Map<String, Map<String, Integer>> getAuthCrossCounts(
        Query query, ResultType resultType, GatewayIdentity identity, String requestId, DistributionType distributionKind
    ) {
        return queryCrossCounts(
            query, resultType, identity, requestId, AccessType.AUTHORIZED, distributionKind, new ParameterizedTypeReference<>() {}
        );
    }

    public Map<String, Map<String, ObfuscatedCount>> getOpenCrossCounts(
        Query query, ResultType resultType, GatewayIdentity identity, String requestId, DistributionType distributionKind
    ) {
        return queryCrossCounts(
            query, resultType, identity, requestId, AccessType.OPEN, distributionKind, new ParameterizedTypeReference<>() {}
        );
    }

    private <T> Map<String, T> queryCrossCounts(
        Query query, ResultType resultType, GatewayIdentity identity, String requestId, AccessType accessType,
        DistributionType distributionKind, ParameterizedTypeReference<Map<String, T>> typeRef
    ) {
        long startTime = System.currentTimeMillis();

        // HQS replaces authorizationFilters using the caller token before it executes or stores this subquery.
        Query subQuery = new Query(
            query.select(), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(), resultType, query.picsureId(),
            query.id()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.ALL));
        addIdentityHeaders(headers, identity);
        if (requestId != null && !requestId.isBlank()) {
            headers.set("X-Request-Id", requestId);
        }

        String path = querySyncPath(accessType);
        String url = queryServiceBaseUrl + path;
        Object body = requestBody(subQuery);
        logger.info(
            "Calling query-service requestId={} accessType={} distributionKind={} resultType={} selectedConceptCount={} selectedConceptPaths={} url={}",
            requestId, accessTypeValue(accessType), distributionKindValue(distributionKind), resultType, selectedConceptCount(subQuery),
            selectedConceptPaths(subQuery), url
        );

        try {
            ResponseEntity<Map<String, T>> response =
                restClient.post().uri(url).headers(h -> h.addAll(headers)).body(body).retrieve().toEntity(typeRef);
            logger.info(
                "query-service call completed requestId={} accessType={} distributionKind={} resultType={} status={} durationMs={} responseSeriesCount={} responsePointCount={} responseSeriesKeys={}",
                requestId, accessTypeValue(accessType), distributionKindValue(distributionKind), resultType,
                response.getStatusCode().value(), System.currentTimeMillis() - startTime, seriesCount(response.getBody()),
                responsePointCount(response.getBody()), responseSeriesKeys(response.getBody())
            );
            sendQueryEvent(
                subQuery, resultType, requestId, accessType, distributionKind, url, path, response.getStatusCode().value(),
                System.currentTimeMillis() - startTime, response.getBody(), null
            );
            return response.getBody() != null ? response.getBody() : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            Integer status = e instanceof HttpStatusCodeException httpError ? httpError.getStatusCode().value() : null;
            logger.warn(
                "query-service call failed requestId={} accessType={} distributionKind={} resultType={} status={} durationMs={} error={}",
                requestId, accessTypeValue(accessType), distributionKindValue(distributionKind), resultType, status,
                System.currentTimeMillis() - startTime, e.getMessage()
            );
            sendQueryEvent(
                subQuery, resultType, requestId, accessType, distributionKind, url, path, null, System.currentTimeMillis() - startTime,
                null, e
            );
            throw e;
        }
    }

    private static void addIdentityHeaders(HttpHeaders headers, GatewayIdentity identity) {
        if (identity == null) {
            return;
        }
        setIfPresent(headers, GatewayUserResolver.HEADER_USER_ID, identity.userId());
        setIfPresent(headers, GatewayUserResolver.HEADER_USER_SUBJECT, identity.subject());
        setIfPresent(headers, GatewayUserResolver.HEADER_USER_EMAIL, identity.email());
        setIfPresent(headers, GatewayUserResolver.HEADER_USER_ROLES, identity.roles());
        setIfPresent(headers, GatewayUserResolver.HEADER_USER_PRIVILEGES, identity.privileges());
        setIfPresent(headers, HttpHeaders.AUTHORIZATION, identity.authorizationHeader());
    }

    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }

    private static String accessTypeValue(AccessType accessType) {
        return accessType != null ? accessType.getValue() : "unknown";
    }

    private static String distributionKindValue(DistributionType distributionKind) {
        return distributionKind != null ? distributionKind.name().toLowerCase() : "unknown";
    }

    private static int selectedConceptCount(Query query) {
        return query.select() != null ? query.select().size() : 0;
    }

    private static List<String> selectedConceptPaths(Query query) {
        return query.select() != null ? query.select() : List.of();
    }

    private static int seriesCount(Map<String, ?> responseBody) {
        return responseBody != null ? responseBody.size() : 0;
    }

    private static List<String> responseSeriesKeys(Map<String, ?> responseBody) {
        return responseBody != null ? new ArrayList<>(responseBody.keySet()) : List.of();
    }

    private Object requestBody(Query subQuery) {
        GeneralQueryRequest request = new GeneralQueryRequest();
        request.setQuery(subQuery);
        return request;
    }

    private String querySyncPath(AccessType accessType) {
        return accessType == AccessType.OPEN ? "/hpds/open/v3/query/sync" : "/hpds/auth/v3/query/sync";
    }

    private void sendQueryEvent(
        Query query, ResultType resultType, String requestId, AccessType accessType, DistributionType distributionKind, String url,
        String path, Integer status, long duration, Map<String, ?> responseBody, RuntimeException error
    ) {
        try {
            Integer resolvedStatus = status;
            if (resolvedStatus == null && error instanceof HttpStatusCodeException httpError) {
                resolvedStatus = httpError.getStatusCode().value();
            }
            LoggingEvent.Builder builder = LoggingEvent.builder("QUERY").action("visualization.query-service.query")
                .request(
                    RequestInfo.builder().requestId(requestId).method("POST").url(path).destIp(destinationHost(url))
                        .destPort(destinationPort(url)).status(resolvedStatus).duration(duration).build()
                ).metadata(queryMetadata(query, resultType, accessType, distributionKind, responseBody));
            if (error != null) {
                builder.error(errorMetadata(error, resolvedStatus));
            }
            loggingClient.send(builder.build(), null, requestId);
        } catch (Exception e) {
            logger.debug("Failed to send query-service audit log event: {}", e.getMessage());
        }
    }

    private static Map<String, Object> queryMetadata(
        Query query, ResultType resultType, AccessType accessType, DistributionType distributionKind, Map<String, ?> responseBody
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("result_type", resultType.toString());
        List<String> selectedConceptPaths = query.select() != null ? query.select() : List.of();
        metadata.put("selected_concept_paths", selectedConceptPaths);
        metadata.put("selected_concept_count", selectedConceptPaths.size());
        if (accessType != null) {
            metadata.put("access_type", accessType.getValue());
        }
        if (distributionKind != null) {
            metadata.put("distribution_kind", distributionKind.name().toLowerCase());
        }
        if (responseBody != null) {
            metadata.put("response_series_count", responseBody.size());
            metadata.put("response_series_keys", new ArrayList<>(responseBody.keySet()));
            metadata.put("response_point_count", responsePointCount(responseBody));
        }
        return metadata;
    }

    private static int responsePointCount(Map<String, ?> responseBody) {
        if (responseBody == null) {
            return 0;
        }
        int count = 0;
        for (Object value : responseBody.values()) {
            if (value instanceof Map<?, ?> series) {
                count += series.size();
            }
        }
        return count;
    }

    private static Map<String, Object> errorMetadata(RuntimeException error, Integer status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (status == null && error instanceof HttpStatusCodeException httpError) {
            status = httpError.getStatusCode().value();
        }
        if (status != null) {
            metadata.put("status", status);
        }
        metadata.put("exception", error.getClass().getSimpleName());
        if (error.getMessage() != null) {
            metadata.put("message", error.getMessage());
        }
        return metadata;
    }

    private static String destinationHost(String url) {
        return URI.create(url).getHost();
    }

    private static Integer destinationPort(String url) {
        URI uri = URI.create(url);
        int port = uri.getPort();
        if (port > 0) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
