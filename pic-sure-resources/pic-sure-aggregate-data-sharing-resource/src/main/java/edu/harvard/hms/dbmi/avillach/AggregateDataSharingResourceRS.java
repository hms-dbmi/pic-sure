package edu.harvard.hms.dbmi.avillach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.util.HttpClientUtil;
import edu.harvard.dbmi.avillach.util.VisualizationUtil;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.hms.dbmi.avillach.service.RequestScopedHeader;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static edu.harvard.dbmi.avillach.service.ResourceWebClient.QUERY_METADATA_FIELD;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.getConfiguredHttpClient;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.readObjectFromResponse;

@Path("/aggregate-data-sharing")
@Produces("application/json")
@Consumes("application/json")
@Singleton
public class AggregateDataSharingResourceRS implements IResourceRS {


    @Inject
    private ApplicationProperties properties;

    @Inject
    private ResourceRepository resourceRepository;

    @Inject
    RequestScopedHeader requestScopedHeader;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Header[] headers;

    private static final String BEARER_STRING = "Bearer ";

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final int threshold;
    private final int variance;

    private final String randomSalt;

    private final HttpClientUtil httpClientUtil;

    public AggregateDataSharingResourceRS() {
        this(null);
    }

    @Inject
    public AggregateDataSharingResourceRS(ApplicationProperties applicationProperties) {
        this.properties = applicationProperties;
        if (applicationProperties == null) {
            logger.info("initialize Aggregate Resource NO INJECTION");
        } else {
            logger.info("initialize Aggregate Resource Injected " + applicationProperties);
        }

        if (properties == null) {
            properties = new ApplicationProperties();
            properties.init("pic-sure-aggregate-resource");
        }

        threshold = properties.getTargetPicsureObfuscationThreshold();
        variance = properties.getTargetPicsureObfuscationVariance();
        randomSalt = properties.getTargetPicsureObfuscationSalt();

        headers = new Header[]{new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + properties.getTargetPicsureToken())};

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100); // Maximum total connections
        connectionManager.setDefaultMaxPerRoute(20); // Maximum connections per route
        httpClientUtil = HttpClientUtil.getInstance(connectionManager);
    }


    @GET
    @Path("/status")
    public Response status() {
        logger.debug("Calling Aggregate Data Sharing Resource status()");
        return Response.ok().build();
    }

    @POST
    @Path("/info")
    @Override
    public ResourceInfo info(QueryRequest infoRequest) {
        logger.debug("Calling Aggregate Data Sharing Resource info()");
        String pathName = "/info";

        try {
            QueryRequest chainRequest = new GeneralQueryRequest();
            if (infoRequest != null) {
                chainRequest.setQuery(infoRequest.getQuery());
                chainRequest.setResourceCredentials(infoRequest.getResourceCredentials());
                //set a default value of the existing uuid here (can override in properties file)
                chainRequest.setResourceUUID(infoRequest.getResourceUUID());
            }
            if (properties.getTargetResourceId() != null && !properties.getTargetResourceId().isEmpty()) {
                chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));
            }

            String payload = objectMapper.writeValueAsString(chainRequest);
            String composedURL = HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName);
            HttpResponse response = httpClientUtil.retrievePostResponse(composedURL, headers, payload);
            if (!httpClientUtil.is2xx(response)) {
                logger.error("{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                        response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
                HttpClientUtil.throwResponseError(response, properties.getTargetPicsureUrl());
            }

            //if we are proxying an info request, we need to return our own resource ID
            ResourceInfo resourceInfo = readObjectFromResponse(response, ResourceInfo.class);
            if (infoRequest != null && infoRequest.getResourceUUID() != null) {
                resourceInfo.setId(infoRequest.getResourceUUID());
            }
            return resourceInfo;
        } catch (IOException e) {
            throw new ApplicationException(
                    "Error encoding query for resource with id " + infoRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        }
    }

    @POST
    @Path("/search")
    @Override
    public SearchResults search(QueryRequest searchRequest) {
        logger.debug("Calling Aggregate Data Sharing Search");
        checkQuery(searchRequest);
        HttpResponse response = postRequest(searchRequest, "/search");
        return readObjectFromResponse(response, SearchResults.class);
    }

    @POST
    @Path("/query")
    @Override
    public QueryStatus query(QueryRequest queryRequest) {
        logger.debug("Calling Aggregate Data Sharing Resource query()");
        checkQuery(queryRequest);
        HttpResponse response = postRequest(queryRequest, "/query");
        return readObjectFromResponse(response, QueryStatus.class);

    }

    @POST
    @Path("/query/{resourceQueryId}/status")
    @Override
    public QueryStatus queryStatus(@PathParam("resourceQueryId") UUID queryId, QueryRequest statusRequest) {
        logger.debug("Calling Aggregate Data Sharing Resource queryStatus() for query {}", queryId);
        checkQuery(statusRequest);
        HttpResponse response = postRequest(statusRequest, "/query/" + queryId + "/status");
        return readObjectFromResponse(response, QueryStatus.class);
    }

    @POST
    @Path("/query/{resourceQueryId}/result")
    @Override
    public Response queryResult(@PathParam("resourceQueryId") UUID queryId, QueryRequest resultRequest) {
        logger.debug("Calling Aggregate Data Sharing Resource queryResult() for query {}", queryId);
        checkQuery(resultRequest);
        HttpResponse response = postRequest(resultRequest, "/query/" + queryId + "/result");
        try {
            return Response.ok(response.getEntity().getContent()).build();
        } catch (IOException e) {
            throw new ApplicationException(
                    "Error encoding query for resource with id " + resultRequest.getResourceUUID()
            );
        }
    }

    private HttpResponse postRequest(QueryRequest statusRequest, String pathName) {
        try {
            QueryRequest chainRequest = createChainRequest(statusRequest);
            String payload = objectMapper.writeValueAsString(chainRequest);
            String composedURL = HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName);
            HttpResponse response = httpClientUtil.retrievePostResponse(composedURL, headers, payload);
            if (!HttpClientUtil.is2xx(response)) {
                logger.error("{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
                        response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
                HttpClientUtil.throwResponseError(response, properties.getTargetPicsureUrl());
            }
            return response;
        } catch (IOException e) {
            // Note: this shouldn't ever happen
            logger.error("Error encoding search payload", e);
            throw new ApplicationException(
                    "Error encoding search for resource with id " + statusRequest.getResourceUUID());
        }
    }

    @POST
    @Path("/query/sync")
    @Override
    public Response querySync(QueryRequest queryRequest) {
        logger.debug("Calling Aggregate Data Sharing Resource querySync()");
        checkQuery(queryRequest);

        try {
            Object query = queryRequest.getQuery();
            UUID resourceUUID = queryRequest.getResourceUUID();

            JsonNode jsonNode = objectMapper.valueToTree(query);
            if (!jsonNode.has("expectedResultType")) {
                throw new ProtocolException(ProtocolException.MISSING_DATA);
            }
            String expectedResultType = jsonNode.get("expectedResultType").asText();

            Set<String> allowedResultTypes = Set.of(
                    "COUNT", "CROSS_COUNT", "INFO_COLUMN_LISTING", "OBSERVATION_COUNT",
                    "OBSERVATION_CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT",
                    "VARIANT_COUNT_FOR_QUERY", "AGGREGATE_VCF_EXCERPT", "VCF_EXCERPT"
            );

            if (!allowedResultTypes.contains(expectedResultType)) {
                logger.warn("Incorrect Result Type: " + expectedResultType);
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            HttpResponse response = getHttpResponse(queryRequest, resourceUUID, "/query/sync", properties.getTargetPicsureUrl());

            HttpEntity entity = response.getEntity();
            String entityString = EntityUtils.toString(entity, "UTF-8");
            String responseString = entityString;

            responseString = getExpectedResponse(expectedResultType, entityString, responseString, queryRequest);

            //propagate any metadata from the back end (e.g., resultId)
            if (response.containsHeader(QUERY_METADATA_FIELD)) {
                Header metadataHeader = ((Header[]) response.getHeaders(QUERY_METADATA_FIELD))[0];
                return Response.ok(responseString).header(QUERY_METADATA_FIELD, metadataHeader.getValue()).build();
            }

            return Response.ok(responseString).build();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new ApplicationException(
                    "Error encoding query for resource with id " + queryRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        }
    }

    private HttpResponse getHttpResponse(QueryRequest queryRequest, UUID resourceUUID, String pathName, String targetPicsureUrl) throws JsonProcessingException {
        String queryString = objectMapper.writeValueAsString(queryRequest);
        String composedURL = HttpClientUtil.composeURL(targetPicsureUrl, pathName);

        logger.debug("Aggregate Data Sharing Resource, sending query: " + queryString + ", to: " + composedURL);
        HttpResponse response = httpClientUtil.retrievePostResponse(composedURL, headers, queryString);
        if (!HttpClientUtil.is2xx(response)) {
            logger.error("Not 200 status!");
            logger.error(
                    composedURL + " calling resource with id " + resourceUUID + " did not return a 200: {} {} ",
                    response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
            HttpClientUtil.throwResponseError(response, targetPicsureUrl);
        }
        return response;
    }

    /**
     * This method will process the response from the backend and return the
     * expected response based on the expected result type.
     * Currently, the only types that are handled are:
     * COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT
     *
     * @param expectedResultType The expected result type
     * @param entityString       The response from the backend that will be processed
     * @param responseString     The response that will be returned. Will return the passed entityString if
     *                           no cases are matched.
     * @return String The response that will be returned
     * @throws JsonProcessingException If there is an error processing the response
     */
    private String getExpectedResponse(String expectedResultType, String entityString, String responseString, QueryRequest queryRequest) throws IOException, JsonProcessingException {
        String crossCountResponse;
        switch (expectedResultType) {
            case "COUNT":
                responseString = aggregateCount(entityString).orElse(entityString);

                break;
            case "CROSS_COUNT":
                Map<String, String> crossCounts = processCrossCounts(entityString);
                responseString = objectMapper.writeValueAsString(crossCounts);

                break;
            case "CATEGORICAL_CROSS_COUNT":
                crossCountResponse = getCrossCountForQuery(queryRequest);
                responseString = processCategoricalCrossCounts(entityString, crossCountResponse);

                break;
            case "CONTINUOUS_CROSS_COUNT":
                crossCountResponse = getCrossCountForQuery(queryRequest);
                responseString = processContinuousCrossCounts(entityString, crossCountResponse, queryRequest);

                break;
        }
        return responseString;
    }

    /**
     * No matter what the expected result type is we will get the cross count instead. Additionally,
     * it will include ALL study consents in the query.
     *
     * @param queryRequest The query request
     * @return String The cross count for the query
     */
    private String getCrossCountForQuery(QueryRequest queryRequest) throws IOException {
        logger.debug("Calling Aggregate Data Sharing Resource getCrossCountForQuery()");

        HttpResponse response = getHttpResponse(changeQueryToOpenCrossCount(queryRequest), queryRequest.getResourceUUID(), "/query/sync", properties.getTargetPicsureUrl());
        HttpEntity entity = response.getEntity();
        return EntityUtils.toString(entity, "UTF-8");
    }

    /**
     * This method will add the study consents to the query. It will also set the expected result type to cross count.
     *
     * @param queryRequest The query request
     * @return QueryRequest The query request with the study consents added and the expected result type set to cross count
     */
    private QueryRequest changeQueryToOpenCrossCount(QueryRequest queryRequest) {
        logger.debug("Calling Aggregate Data Sharing Resource handleAlterQueryToOpenCrossCount()");

        Object query = queryRequest.getQuery();
        JsonNode jsonNode = objectMapper.valueToTree(query);

        JsonNode updatedExpectedResulType = setExpectedResultTypeToCrossCount(jsonNode);
        JsonNode includesStudyConsents = addStudyConsentsToQuery(updatedExpectedResulType);

        LinkedHashMap<String, Object> rebuiltQuery = objectMapper.convertValue(includesStudyConsents, new TypeReference<>() {
        });
        queryRequest.setQuery(rebuiltQuery);
        return queryRequest;
    }

    private JsonNode setExpectedResultTypeToCrossCount(JsonNode jsonNode) {
        logger.debug("Calling Aggregate Data Sharing Resource setExpectedResultTypeToCrossCount()");

        List<JsonNode> expectedResultTypeParents = jsonNode.findParents("expectedResultType");

        // The reason we need to do this is that expected result type is a TextNode that is immutable.
        // This is a jackson work around to replace the expectedResultType field with a new value.
        for (JsonNode node : expectedResultTypeParents) {
            ((ObjectNode) node).put("expectedResultType", "CROSS_COUNT");
        }

        return jsonNode;
    }

    private JsonNode addStudyConsentsToQuery(JsonNode jsonNode) {
        logger.debug("Calling Aggregate Data Sharing Resource addStudyConsentsToQuery()");

        SearchResults consentResults = getAllStudyConsents();
        LinkedHashMap<String, Object> linkedHashMap = objectMapper.convertValue(consentResults.getResults(), new TypeReference<>() {
        });
        Object phenotypes = linkedHashMap.get("phenotypes");
        LinkedHashMap<String, Object> phenotypesLinkedHashMap = objectMapper.convertValue(phenotypes, new TypeReference<>() {
        });

        // get all the keys from phenotypes
        Set<String> keys = phenotypesLinkedHashMap.keySet();

        // create an ArrayNode to hold the keys
        ArrayNode arrayNode = objectMapper.createArrayNode();

        // add the keys to the ArrayNode
        for (String key : keys) {
            arrayNode.add(key);
        }

        // add the ArrayNode to the query
        ((ObjectNode) jsonNode).set("crossCountFields", arrayNode);

        return jsonNode;
    }

    private SearchResults getAllStudyConsents() {
        logger.debug("Calling Aggregate Data Sharing Resource getAllStudyConsents()");

        QueryRequest studiesConsents = new GeneralQueryRequest();
        studiesConsents.setQuery("\\_studies_consents\\");
        return this.search(studiesConsents);
    }

    @Override
    @POST
    @Path("/query/format")
    public Response queryFormat(QueryRequest queryRequest) {
        checkQuery(queryRequest);

        UUID resourceUUID = queryRequest.getResourceUUID();
        String pathName = "/query/format";

        try {
            String queryString = objectMapper.writeValueAsString(queryRequest);
            String composedURL = HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName);
            HttpResponse response = httpClientUtil.retrievePostResponse(composedURL, headers, queryString);
            if (!HttpClientUtil.is2xx(response)) {
                logger.error(
                        composedURL + " calling resource with id " + resourceUUID + " did not return a 200: {} {} ",
                        response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
                HttpClientUtil.throwResponseError(response, properties.getTargetPicsureUrl());
            }

            return Response.ok(response.getEntity().getContent()).build();
        } catch (IOException e) {
            throw new ApplicationException(
                    "Error encoding query for resource with id " + queryRequest.getResourceUUID());
        } catch (ClassCastException | IllegalArgumentException e) {
            logger.error(e.getMessage());
            throw new ProtocolException(ProtocolException.INCORRECTLY_FORMATTED_REQUEST);
        }
    }

    private Map<String, String> processCrossCounts(String entityString) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, String> crossCounts = objectMapper.readValue(entityString, new TypeReference<>() {
        });

        int requestVariance = generateVarianceWithCrossCounts(crossCounts);
        crossCounts = obfuscateCrossCounts(crossCounts, requestVariance);

        return crossCounts;
    }

    /**
     * This method will appropriately process the obfuscation of the cross counts.
     *
     * @param crossCounts     The cross counts
     * @param requestVariance The variance for the request
     * @return Map<String, String> The obfuscated cross counts
     */
    private Map<String, String> obfuscateCrossCounts(Map<String, String> crossCounts, int requestVariance) {
        Set<String> obfuscatedKeys = new HashSet<>();
        if (crossCounts != null) {
            crossCounts.keySet().forEach(key -> {
                String crossCount = crossCounts.get(key);
                Optional<String> aggregatedCount = aggregateCount(crossCount);
                aggregatedCount.ifPresent((x) -> obfuscatedKeys.add(key));
                crossCounts.put(key, aggregatedCount.orElse(crossCount));
            });

            Set<String> obfuscatedParents = obfuscatedKeys.stream().flatMap(this::generateParents).collect(Collectors.toSet());

            crossCounts.keySet().forEach(key -> {
                String crossCount = crossCounts.get(key);
                if (!obfuscatedKeys.contains(key) && obfuscatedParents.contains(key)) {
                    crossCounts.put(key, randomize(crossCount, requestVariance));
                }
            });
        }

        return crossCounts;
    }

    /**
     * This method is used to generate a variance for Cross Count queries.
     * The variance is generated by taking the cross counts and sorting them by key.
     * Then we generate a string with lines like consent:1\n consent:2\ consent:3\n etc.
     * Then we generate a variance using the string. This is to give us a random variance that is deterministic for each
     * query.
     *
     * @param crossCounts A map of cross counts
     * @return int The variance
     */
    private int generateVarianceWithCrossCounts(Map<String, String> crossCounts) {
        final List<Map.Entry<String, String>> entryList = new ArrayList<>(crossCounts.entrySet());

        // sort the entry set. By sorting the entry set first we can ensure that the variance is the same for each run.
        // This is to give us a random variance that is deterministic.
        entryList.sort(Map.Entry.comparingByKey());

        final StringBuffer crossCountsString = new StringBuffer();

        // build a string with lines like consent:1\n consent:2\n consent:3\n etc.
        entryList.forEach(entry -> crossCountsString.append(entry.getKey()).append(":").append(entry.getValue()).append("\n"));

        return generateRequestVariance(crossCountsString.toString());
    }

    /**
     * This method will return an obfuscated binned count of continuous crosses. Due to the format of a continuous
     * cross count, we are unable to directly obfuscate it in its original form. First, we send the continuous
     * cross count data to the visualization resource to group it into bins. Once the data is binned, we assess whether
     * obfuscation is necessary for this particular continuous cross count. If obfuscation is not required, we return
     * the data in string format. However, if obfuscation is needed, we first obfuscate the data and then return it.
     *
     * @param continuousCrossCountResponse The continuous cross count response
     * @param crossCountResponse           The cross count response
     * @param queryRequest                 The original query request
     * @return String The obfuscated binned continuous cross count
     * @throws IOException If there is an error processing the JSON
     */
    protected String processContinuousCrossCounts(String continuousCrossCountResponse, String crossCountResponse, QueryRequest queryRequest) throws IOException {
        logger.info("Processing continuous cross counts");

        if (continuousCrossCountResponse == null || crossCountResponse == null) {
            return null;
        }

        Map<String, String> crossCounts = objectMapper.readValue(crossCountResponse, new TypeReference<>() {
        });
        int generatedVariance = this.generateVarianceWithCrossCounts(crossCounts);
        boolean mustObfuscate = isCrossCountObfuscated(crossCounts, generatedVariance);

        if (canShowContinuousCrossCounts(crossCounts)) {
            return null;
        }

        // Handle the case where there is no visualization service UUID
        if (properties.getVisualizationResourceId() != null) {
            Map<String, Map<String, Integer>> continuousCrossCounts = objectMapper.readValue(continuousCrossCountResponse, new TypeReference<>() {});
            Map<String, Map<String, Object>> binnedContinuousCrossCounts = getBinnedContinuousCrossCount(queryRequest, continuousCrossCounts);

            // Log the binned continuous cross counts
            logger.info("Binned continuous cross counts: {}", binnedContinuousCrossCounts);

            if (mustObfuscate || doObfuscateData(binnedContinuousCrossCounts)) {
                obfuscatedCrossCount(generatedVariance, binnedContinuousCrossCounts);
            }

            return objectMapper.writeValueAsString(binnedContinuousCrossCounts);
        } else {
            // If there is no visualization service resource id, we will simply return the continuous cross count response.
            if (!mustObfuscate) {
                return continuousCrossCountResponse;
            }

            Map<String, Map<String, Object>> continuousCrossCounts = objectMapper.readValue(continuousCrossCountResponse, new TypeReference<>() {});

            obfuscatedCrossCount(generatedVariance, continuousCrossCounts);
            return objectMapper.writeValueAsString(continuousCrossCounts);
        }
    }

    private Map<String, Map<String, Object>> getBinnedContinuousCrossCount(QueryRequest queryRequest, Map<String, Map<String, Integer>> continuousCrossCounts) throws IOException {
        // Create Query for Visualization /bin/continuous
        QueryRequest visualizationBinRequest = new GeneralQueryRequest();
        visualizationBinRequest.setResourceUUID(properties.getVisualizationResourceId());
        visualizationBinRequest.setQuery(continuousCrossCounts);
        visualizationBinRequest.setResourceCredentials(queryRequest.getResourceCredentials());

        Resource visResource = resourceRepository.getById(visualizationBinRequest.getResourceUUID());
        if (visResource == null) {
            throw new ApplicationException("Visualization resource could not be found");
        }

        // call the binning endpoint
        HttpResponse httpResponse = getHttpResponse(visualizationBinRequest, visualizationBinRequest.getResourceUUID(), "/bin/continuous", visResource.getResourceRSPath());
        HttpEntity entity = httpResponse.getEntity();
        String binResponse = EntityUtils.toString(entity, "UTF-8");

        return objectMapper.readValue(binResponse, new TypeReference<Map<String, Map<String, Object>>>() {
        });
    }

    /**
     * This method handles the processing of categorical cross counts. It begins by determining whether the cross
     * counts require obfuscation. This is accomplished by checking if any of the CROSS_COUNTS must be obfuscated.
     * If obfuscation is required, the categorical cross counts will be obfuscated accordingly. Otherwise,
     * if no obfuscation is needed, the method can simply return the categorical entity string.
     *
     * @param categoricalEntityString The categorical entity string
     * @param crossCountEntityString  The cross count entity string
     * @return String The processed categorical entity string
     * @throws JsonProcessingException If there is an error processing the JSON
     */
    protected String processCategoricalCrossCounts(String categoricalEntityString, String crossCountEntityString) throws JsonProcessingException {
        logger.info("Processing categorical cross counts");

        if (categoricalEntityString == null || crossCountEntityString == null) {
            return null;
        }

        Map<String, String> crossCounts = objectMapper.readValue(crossCountEntityString, new TypeReference<>() {
        });
        int generatedVariance = this.generateVarianceWithCrossCounts(crossCounts);

        Map<String, Map<String, Object>> categoricalCrossCount = objectMapper.readValue(categoricalEntityString, new TypeReference<>() {
        });

        if (categoricalCrossCount == null) {
            logger.info("Categorical cross count is null. Returning categoricalEntityString: {}", categoricalEntityString);
            return categoricalEntityString;
        }

        // We have not obfuscated yet. We first process the data.
        processResults(categoricalCrossCount);

        if (isCrossCountObfuscated(crossCounts, generatedVariance) || doObfuscateData(categoricalCrossCount)) {
            // Now we need to obfuscate our return data. The only consideration is do we apply < threshold or variance
            obfuscatedCrossCount(generatedVariance, categoricalCrossCount);
        }

        return objectMapper.writeValueAsString(categoricalCrossCount);
    }

    private static void processResults(Map<String, Map<String, Object>> categoricalCrossCount) {
        for (Map.Entry<String, Map<String, Object>> entry : categoricalCrossCount.entrySet()) {
            // skipKey is expecting an entrySet, so we need to convert the axisMap to an entrySet
            if (VisualizationUtil.skipKey(entry.getKey())) continue;
            Map<String, Object> axisMap = VisualizationUtil.processResults(entry.getValue());
            categoricalCrossCount.put(entry.getKey(), axisMap);
        }
    }

    /**
     * @param categoricalCrossCount The categorical cross count
     * @return boolean based on if the categorical cross count needs to be obfuscated
     */
    private boolean doObfuscateData(Map<String, Map<String, Object>> categoricalCrossCount) {
        if (!isRequestSourceOpen(getRequestSource())) {
            return false;
        }

        return categoricalCrossCount.values().stream()
                .anyMatch(this::checkForObfuscation);
    }

    private boolean checkForObfuscation(Map<String, Object> axisMap) {
        for (Map.Entry<String, Object> axisEntry : axisMap.entrySet()) {
            Object value = axisEntry.getValue();
            if (value instanceof Integer && (Integer) value < threshold) {
                return true;
            }
            if (value instanceof String && Integer.parseInt((String) value) < threshold) {
                return true;
            }
        }
        return false;
    }

    private boolean isRequestSourceOpen(String requestSource) {
        return requestSource != null && requestSource.equalsIgnoreCase("Open");
    }

    private String getRequestSource() {
        if (requestScopedHeader == null || requestScopedHeader.getHeaders() == null) {
            logger.warn("Request scoped header or headers are null");
            return null;
        }
        List<String> requestSources = requestScopedHeader.getHeaders().get("request-source");
        if (requestSources == null || requestSources.isEmpty()) {
            logger.warn("Request source header is missing or empty");
            return null;
        }
        String requestSource = requestSources.get(0);
        logger.info("Request source: " + requestSource);
        return requestSource;
    }

    /**
     * This method will obfuscate the cross counts based on the generated variance. We do not have a return because
     * we are modifying the passed crossCount object. Java passes objects by reference value, so we do not need to return.
     *
     * @param generatedVariance The variance for the request
     * @param crossCount        The cross count that will be obfuscated
     */
    private void obfuscatedCrossCount(int generatedVariance, Map<String, Map<String, Object>> crossCount) {
        crossCount.forEach((key, value) -> {
            value.forEach((innerKey, innerValue) -> {
                Optional<String> aggregateCount = aggregateCountHelper(innerValue);
                if (aggregateCount.isPresent()) {
                    value.put(innerKey, aggregateCount.get());
                } else {
                    value.put(innerKey, randomize(innerValue.toString(), generatedVariance));
                }
            });
        });
    }

    /**
     * This method will determine if the cross count needs to be obfuscated. It will return true if any of the
     * cross counts are less than the threshold or if any of the cross counts have a variance.
     *
     * @param crossCounts     The cross counts
     * @param generatedVariance The variance for the request
     * @return boolean True if the cross count needs to be obfuscated, false otherwise
     */
    private boolean isCrossCountObfuscated(Map<String, String> crossCounts, int generatedVariance) {
        String lessThanThresholdStr = "< " + this.threshold;
        String varianceStr = " \u00B1" + variance;

        boolean mustObfuscate = false;
        Map<String, String> obfuscatedCrossCount = this.obfuscateCrossCounts(crossCounts, generatedVariance);
        for (Map.Entry<String, String> entry : obfuscatedCrossCount.entrySet()) {
            String v = entry.getValue();
            if (v.contains(lessThanThresholdStr) || v.contains(varianceStr)) {
                mustObfuscate = true;
                break;
            }
        }

        return mustObfuscate;
    }


    private boolean canShowContinuousCrossCounts(Map<String, String> crossCounts) {
        String lessThanThresholdStr = "< " + this.threshold;

        String v = crossCounts.get("\\_studies_consents\\");
        if (v.contains(lessThanThresholdStr) || v.equals("0")) {
            return true;
        }
        return false;
    }

    /**
     * This method will generate a random variance for the request based on the passed entityString. The variance
     * will be between -variance and +variance. The variance will be generated by adding a random salt to the
     * entityString and then taking the hashcode of the result. The variance will be the hashcode mod the
     * variance * 2 + 1 - variance.
     *
     * @return int The variance for the request
     */
    private QueryRequest createChainRequest(QueryRequest queryRequest) {
        QueryRequest chainRequest = new GeneralQueryRequest();
        chainRequest.setQuery(queryRequest.getQuery());
        chainRequest.setResourceCredentials(queryRequest.getResourceCredentials());

        if (properties.getTargetResourceId() != null && !properties.getTargetResourceId().isEmpty()) {
            chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));
        } else {
            chainRequest.setResourceUUID(queryRequest.getResourceUUID());
        }
        return chainRequest;
    }

    private static void checkQuery(QueryRequest searchRequest) {
        if (searchRequest == null || searchRequest.getQuery() == null) {
            throw new ProtocolException(ProtocolException.MISSING_DATA);
        }
    }

    private int generateRequestVariance(String entityString) {
        return Math.abs((entityString + randomSalt).hashCode()) % (variance * 2 + 1) - variance;
    }

    private String randomize(String crossCount, int requestVariance) {
        return Math.max((Integer.parseInt(crossCount) + requestVariance), threshold) + " \u00B1" + variance;
    }

    private Stream<String> generateParents(String key) {
        StringJoiner stringJoiner = new StringJoiner("\\", "\\", "\\");

        String[] split = key.split("\\\\");
        if (split.length > 1) {
            return Arrays.stream(Arrays.copyOfRange(split, 0, split.length - 1))
                    .filter(Predicate.not(String::isEmpty))
                    .map(segment -> stringJoiner.add(segment).toString());
        }
        return Stream.empty();
    }

    /**
     * Here's the core of this resource - make sure we do not return results with small (potentially identifiable) cohorts.
     *
     * @param actualCount
     * @return
     */
    private Optional<String> aggregateCount(String actualCount) {
        try {
            int queryResult = Integer.parseInt(actualCount);
            if (queryResult < threshold) {
                return Optional.of("< " + threshold);
            }
        } catch (NumberFormatException nfe) {
            logger.warn("Count was not a number! " + actualCount);
        }
        return Optional.empty();
    }

    /**
     * Helper method to handle the fact that the actualCount could be an Integer or a String.
     *
     * @param actualCount
     * @return
     */
    private Optional<String> aggregateCountHelper(Object actualCount) {
        if (actualCount instanceof Integer) {
            return aggregateCount(actualCount.toString());
        } else if (actualCount instanceof String) {
            return aggregateCount((String) actualCount);
        }
        return Optional.empty();
    }
}
