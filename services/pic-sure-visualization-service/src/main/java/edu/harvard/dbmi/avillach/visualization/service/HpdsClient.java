package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import edu.harvard.dbmi.avillach.visualization.error.BadVisualizationRequestException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.DistributionType;
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
import org.springframework.web.client.RestTemplate;

@Component
public class HpdsClient {

    private static final Logger logger = LoggerFactory.getLogger(HpdsClient.class);

    private final RestTemplate restTemplate;
    private final LoggingClient loggingClient;
    private final String hpdsBaseUrl;

    public HpdsClient(RestTemplate restTemplate, LoggingClient loggingClient, @Value("${hpds.base-url}") String hpdsBaseUrl) {
        this.restTemplate = restTemplate;
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

    public Map<String, Map<String, String>> getOpenCrossCounts(Query query, ResultType resultType, UUID resourceUUID, String bearerToken) {
        return getOpenCrossCounts(query, resultType, resourceUUID, bearerToken, null, AccessType.OPEN, null);
    }

    public Map<String, Map<String, String>> getOpenCrossCounts(
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
        validateResourceUUID(resourceUUID);
        long startTime = System.currentTimeMillis();

        Query subQuery = new Query(
            query.select(), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(), resultType, query.picsureId(),
            query.id()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set("Authorization", bearerToken);
        }
        if (requestId != null && !requestId.isBlank()) {
            headers.set("X-Request-Id", requestId);
        }

        String url = hpdsBaseUrl + querySyncPath(accessType);
        Object body = requestBody(subQuery, resourceUUID);
        logger.debug("HPDS query to {} with resultType={}, resourceUUID={}", url, resultType, resourceUUID);

        try {
            ResponseEntity<Map<String, T>> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), typeRef);
            sendHpdsEvent(
                subQuery, resultType, resourceUUID, requestId, bearerToken, accessType, distributionKind, url,
                response.getStatusCode().value(), System.currentTimeMillis() - startTime, response.getBody(), null
            );
            return response.getBody() != null ? response.getBody() : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            sendHpdsEvent(
                subQuery, resultType, resourceUUID, requestId, bearerToken, accessType, distributionKind, url, null,
                System.currentTimeMillis() - startTime, null, e
            );
            throw e;
        }
    }

    private Object requestBody(Query subQuery, UUID resourceUUID) {
        GeneralQueryRequest request = new GeneralQueryRequest();
        request.setResourceUUID(resourceUUID);
        request.setQuery(subQuery);
        request.setResourceCredentials(Map.of());
        return request;
    }

    private static void validateResourceUUID(UUID resourceUUID) {
        if (resourceUUID == null) {
            throw new BadVisualizationRequestException("HPDS resource UUID is required");
        }
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
