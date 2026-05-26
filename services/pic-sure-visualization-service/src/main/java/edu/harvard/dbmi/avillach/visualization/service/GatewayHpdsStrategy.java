package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@ConditionalOnProperty(name = "hpds.call-mode", havingValue = "gateway")
public class GatewayHpdsStrategy implements HpdsCallStrategy {

    private static final Logger logger = LoggerFactory.getLogger(GatewayHpdsStrategy.class);

    private final RestTemplate restTemplate;
    private final String picsureApiUrl;

    public GatewayHpdsStrategy(RestTemplate restTemplate, @Value("${picsure.api.url}") String picsureApiUrl) {
        this.restTemplate = restTemplate;
        this.picsureApiUrl = picsureApiUrl;
    }

    @Override
    public <T> Map<String, T> queryCrossCounts(
        Query query, ResultType resultType, UUID resourceUUID, String bearerToken, ParameterizedTypeReference<Map<String, T>> typeRef
    ) {
        Query subQuery = new Query(
            query.select(), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(), resultType, query.picsureId(),
            query.id()
        );

        GeneralQueryRequest gatewayRequest = new GeneralQueryRequest();
        gatewayRequest.setResourceUUID(resourceUUID);
        gatewayRequest.setQuery(subQuery);
        gatewayRequest.setResourceCredentials(Map.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set("Authorization", bearerToken);
        }

        String url = picsureApiUrl + "/v3/query/sync";
        logger.debug("Gateway HPDS call to {} with resultType={}, resourceUUID={}", url, resultType, resourceUUID);

        ResponseEntity<Map<String, T>> response =
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(gatewayRequest, headers), typeRef);
        return response.getBody() != null ? response.getBody() : new LinkedHashMap<>();
    }
}
