package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.ApplicationProperties;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.ResultType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import javax.ejb.Stateless;
import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.AccessType.AUTHORIZED_ACCESS;
import static edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.AccessType.OPEN_ACCESS;

@Stateless
public class HpdsService {

    private Logger logger = LoggerFactory.getLogger(HpdsService.class);

    private static final String AUTH_HEADER_NAME = "Authorization";
    private RestTemplate restTemplate;

    @Inject
    private ApplicationProperties applicationProperties;

    private final ObjectMapper mapper = new ObjectMapper();

    public HpdsService() {
        if (restTemplate == null) {
            restTemplate = new RestTemplate();
        }
        if (applicationProperties == null) {
            applicationProperties = new ApplicationProperties();
            applicationProperties.init("pic-sure-visualization-resource");
        }
    }

    /**
     * Takes the current query and creates a CONTINUOUS_CROSS_COUNT query and sends that to HPDS.
     *
     * @param queryRequest - {@link QueryRequest} - contains the query filters to be sent to HPDS
     * @return List<ContinuousData> - A LinkedHashMap of the cross counts for category or continuous
     * date range and their respective counts
     */
    private <T> Map<String, T> getCrossCountsMap(QueryRequest queryRequest, ResultType resultType, String type, ParameterizedTypeReference<Map<String, T>> typeRef) {
        if (queryRequest == null) {
            logger.error("QueryRequest is null");
            return new LinkedHashMap<>();
        }

        try {
            logger.debug("Getting {} cross counts map from query:", type, queryRequest);
            sanityCheck(queryRequest, resultType, type);
            HttpHeaders requestHeaders = prepareQueryRequest(queryRequest, resultType, type);
            String url = applicationProperties.getOrigin() + "/query/sync";
            queryRequest.getResourceCredentials().remove("BEARER_TOKEN");
            return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(queryRequest, requestHeaders), typeRef).getBody();
        } catch (Exception e) {
            logger.error("Error getting cross counts: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public Map<String, Map<String, Integer>> getAuthCrossCountsMap(QueryRequest queryRequest, ResultType resultType) {
        return getCrossCountsMap(queryRequest, resultType, AUTHORIZED_ACCESS.getValue(), new ParameterizedTypeReference<Map<String, Map<String, Integer>>>() {
        });
    }

    public Map<String, Map<String, String>> getOpenCrossCountsMap(QueryRequest queryRequest, ResultType resultType) {
        return getCrossCountsMap(queryRequest, resultType, OPEN_ACCESS.getValue(), new ParameterizedTypeReference<Map<String, Map<String, String>>>() {
        });
    }

    /**
     * Create a new HttpHeaders object with the passed in queryRequest and ResultType. Transfers the authorization token
     * from the queryRequest and creates a new authorization header. Adds the resultType to the queryRequest.
     * Sets the correct Resource UUID
     *
     * @param queryRequest - {@link QueryRequest} - contains the auth header
     * @param resultType   - {@link ResultType} - determines the type of query to be sent to HPDS
     * @return HttpHeaders - the headers to be sent to HPDS
     */
    private HttpHeaders prepareQueryRequest(QueryRequest queryRequest, ResultType resultType, String accessType) {
        HttpHeaders headers = new HttpHeaders();
        if (AUTHORIZED_ACCESS.getValue().equals(accessType)) {
            headers.add(AUTH_HEADER_NAME,
                queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME)
            );
        }

        headers.add("Request-Source", accessType);
        headers.add("Content-type", "application/json");

        Query query;
        try {
            query = mapper.readValue(mapper.writeValueAsString(queryRequest.getQuery()), Query.class);
            query.expectedResultType = resultType;
            queryRequest.setQuery(query);
        } catch (Exception e) {
            throw new IllegalArgumentException("QueryRequest must contain a Query object");
        }
        queryRequest.setResourceUUID(getAppropriateResourceUUID(accessType));
        return headers;
    }

    private UUID getAppropriateResourceUUID(String accessType) {
        if (AUTHORIZED_ACCESS.getValue().equals(accessType)) {
            return applicationProperties.getAuthHpdsResourceId();
        } else {
            // Use OpenHpds as the default. This is to ensure that the resource is always available and that
            // we don't return Authorized data to an Open user.
            return applicationProperties.getOpenHpdsResourceId();
        }
    }

    private void sanityCheck(QueryRequest queryRequest, ResultType requestType, String accessType) {
        if (accessType == null || accessType.trim().equals(""))
            throw new IllegalArgumentException("request-source header is required");
        if (!(AUTHORIZED_ACCESS.getValue().equals(accessType) || OPEN_ACCESS.getValue().equals(accessType)))
            throw new IllegalArgumentException("accessType must be either Open or Authorized");
        if (applicationProperties.getOrigin() == null) throw new IllegalArgumentException("picSureUrl is required");
        if (applicationProperties.getAuthHpdsResourceId() == null)
            throw new IllegalArgumentException("picSureUuid is required");
        if (AUTHORIZED_ACCESS.getValue().equals(accessType) && queryRequest.getResourceCredentials().get(AUTH_HEADER_NAME) == null)
            throw new IllegalArgumentException("No authorization token found in queryRequest");
        if (requestType == null) throw new IllegalArgumentException("ResultType is required");
        if (requestType != ResultType.CATEGORICAL_CROSS_COUNT && requestType != ResultType.CONTINUOUS_CROSS_COUNT)
            throw new IllegalArgumentException("ResultType must be CATEGORICAL_CROSS_COUNT or CONTINUOUS_CROSS_COUNT");
    }
}
