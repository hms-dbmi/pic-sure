package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.DistributionType;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Component
public class HpdsClient {

    private static final Logger logger = LoggerFactory.getLogger(HpdsClient.class);

    private final RestClient restClient;
    private final LoggingClient loggingClient;
    private final String hpdsBaseUrl;

    public HpdsClient(RestClient restClient, LoggingClient loggingClient, @Value("${hpds.base-url}") String hpdsBaseUrl) {
        this.restClient = restClient;
        this.loggingClient = loggingClient;
        this.hpdsBaseUrl = hpdsBaseUrl;
    }

    public Map<String, Map<String, Integer>> getAuthCrossCounts(Query query, ResultType resultType, UUID resourceUUID, String bearerToken) {
        return getAuthCrossCounts(query, resultType, resourceUUID, bearerToken, null, AccessType.AUTHORIZED, null);
    }

    public Map<String, Map<String, Integer>> getAuthCrossCounts(
        Query query, ResultType resultType, UUID resourceUUID, String bearerToken, String requestId, AccessType accessType,
        DistributionType distributionKind
    ) {
        return queryCrossCounts(
            query, resultType, resourceUUID, bearerToken, requestId, accessType, distributionKind, new ParameterizedTypeReference<>() {}
        );
    }

    public Map<String, Map<String, ObfuscatedCount>> getOpenCrossCounts(Query query, ResultType resultType, UUID resourceUUID, String bearerToken) {
        return getOpenCrossCounts(query, resultType, resourceUUID, bearerToken, null, AccessType.OPEN, null);
    }

    public Map<String, Map<String, ObfuscatedCount>> getOpenCrossCounts(
        Query query, ResultType resultType, UUID resourceUUID, String bearerToken, String requestId, AccessType accessType,
        DistributionType distributionKind
    ) {
        return queryCrossCounts(
            query, resultType, resourceUUID, bearerToken, requestId, accessType, distributionKind, new ParameterizedTypeReference<>() {}
        );
    }

    private <T> Map<String, T> queryCrossCounts(
        Query query, ResultType resultType, UUID resourceUUID, String bearerToken, String requestId, AccessType accessType,
        DistributionType distributionKind, ParameterizedTypeReference<Map<String, T>> typeRef
    ) {
        long startTime = System.currentTimeMillis();

        Query subQuery = new Query(
            query.select(), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(), resultType, query.picsureId(),
            query.id()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.ALL));
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set("Authorization", bearerToken);
        }
        if (requestId != null && !requestId.isBlank()) {
            headers.set("X-Request-Id", requestId);
        }

        String url = hpdsBaseUrl + querySyncPath(accessType);
        Object body = requestBody(subQuery, resourceUUID);
        logger.info(
            "Calling HPDS requestId={} accessType={} distributionKind={} resultType={} resourceUUID={} selectedConceptCount={} selectedConceptPaths={} url={}",
            requestId, accessTypeValue(accessType), distributionKindValue(distributionKind), resultType, resourceUUID,
            selectedConceptCount(subQuery), selectedConceptPaths(subQuery), url
        );

        try {
            ResponseEntity<Map<String, T>> response =
                restClient.post().uri(url).headers(h -> h.addAll(headers)).body(body).retrieve().toEntity(typeRef);
            logger.info(
                "HPDS call completed requestId={} accessType={} distributionKind={} resultType={} status={} durationMs={} responseSeriesCount={} responsePointCount={} responseSeriesKeys={}",
                requestId, accessTypeValue(accessType), distributionKindValue(distributionKind), resultType,
                response.getStatusCode().value(), System.currentTimeMillis() - startTime, seriesCount(response.getBody()),
                responsePointCount(response.getBody()), responseSeriesKeys(response.getBody())
            );
            sendHpdsEvent(
                subQuery, resultType, resourceUUID, requestId, bearerToken, accessType, distributionKind, url,
                response.getStatusCode().value(), System.currentTimeMillis() - startTime, response.getBody(), null
            );
            return response.getBody() != null ? response.getBody() : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            Integer status = e instanceof HttpStatusCodeException httpError ? httpError.getStatusCode().value() : null;
            logger.warn(
                "HPDS call failed requestId={} accessType={} distributionKind={} resultType={} status={} durationMs={} error={}",
                requestId, accessTypeValue(accessType), distributionKindValue(distributionKind), resultType, status,
                System.currentTimeMillis() - startTime, e.getMessage()
            );
            sendHpdsEvent(
                subQuery, resultType, resourceUUID, requestId, bearerToken, accessType, distributionKind, url, null,
                System.currentTimeMillis() - startTime, null, e
            );
            throw e;
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

    private Object requestBody(Query subQuery, UUID resourceUUID) {
        GeneralQueryRequest request = new GeneralQueryRequest();
        request.setResourceUUID(resourceUUID);
        request.setQuery(subQuery);
        request.setResourceCredentials(Map.of());
        return request;
    }

    private String querySyncPath(AccessType accessType) {
        return accessType == AccessType.OPEN ? "/query/sync" : "/v3/query/sync";
    }

    private void sendHpdsEvent(
        Query query, ResultType resultType, UUID resourceUUID, String requestId, String bearerToken, AccessType accessType,
        DistributionType distributionKind, String url, Integer status, long duration, Map<String, ?> responseBody, RuntimeException error
    ) {
        try {
            Integer resolvedStatus = status;
            if (resolvedStatus == null && error instanceof HttpStatusCodeException httpError) {
                resolvedStatus = httpError.getStatusCode().value();
            }
            LoggingEvent.Builder builder = LoggingEvent.builder("QUERY").action("visualization.hpds.query")
                .request(
                    RequestInfo.builder().requestId(requestId).method("POST").url("/v3/query/sync").destIp(destinationHost(url))
                        .destPort(destinationPort(url)).status(resolvedStatus).duration(duration).build()
                ).metadata(hpdsMetadata(query, resultType, resourceUUID, accessType, distributionKind, responseBody));
            if (error != null) {
                builder.error(errorMetadata(error, resolvedStatus));
            }
            loggingClient.send(builder.build(), bearerToken, requestId);
        } catch (Exception e) {
            logger.debug("Failed to send HPDS audit log event: {}", e.getMessage());
        }
    }

    private static Map<String, Object> hpdsMetadata(
        Query query, ResultType resultType, UUID resourceUUID, AccessType accessType, DistributionType distributionKind,
        Map<String, ?> responseBody
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource_uuid", resourceUUID.toString());
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
