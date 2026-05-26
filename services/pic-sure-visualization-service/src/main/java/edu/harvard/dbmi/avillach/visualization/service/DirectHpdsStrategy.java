package edu.harvard.dbmi.avillach.visualization.service;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "hpds.call-mode", havingValue = "direct", matchIfMissing = true)
public class DirectHpdsStrategy implements HpdsCallStrategy {

    private static final Logger logger = LoggerFactory.getLogger(DirectHpdsStrategy.class);

    private final RestTemplate restTemplate;
    private final String hpdsBaseUrl;

    public DirectHpdsStrategy(RestTemplate restTemplate, @Value("${hpds.direct.url}") String hpdsBaseUrl) {
        this.restTemplate = restTemplate;
        this.hpdsBaseUrl = hpdsBaseUrl;
    }

    @Override
    public <T> Map<String, T> queryCrossCounts(
        Query query, ResultType resultType, UUID resourceUUID, String bearerToken, ParameterizedTypeReference<Map<String, T>> typeRef
    ) {
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

        String url = hpdsBaseUrl + "/v3/query/sync";
        logger.debug("Direct HPDS call to {} with resultType={}", url, resultType);

        ResponseEntity<Map<String, T>> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(subQuery, headers), typeRef);
        return response.getBody() != null ? response.getBody() : new LinkedHashMap<>();
    }
}
