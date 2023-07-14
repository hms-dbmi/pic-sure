package edu.harvard.hms.dbmi.avillach;

import static edu.harvard.dbmi.avillach.service.ResourceWebClient.QUERY_METADATA_FIELD;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.composeURL;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.readObjectFromResponse;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.retrievePostResponse;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.throwResponseError;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

@Path("/aggregate-data-sharing")
@Produces("application/json")
@Consumes("application/json")
@Singleton
public class AggregateDataSharingResourceRS implements IResourceRS {

	@Inject
	private ApplicationProperties properties;

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private Header[] headers;

	private static final String BEARER_STRING = "Bearer ";

	private Logger logger = LoggerFactory.getLogger(this.getClass());

	private final int threshold;
	private final int variance;

	private final String randomSalt;

	public static final List<String> ALLOWED_RESULT_TYPES = Arrays.asList(new String [] {
		"COUNT", "CROSS_COUNT", "INFO_COLUMN_LISTING", "OBSERVATION_COUNT",
			"OBSERVATION_CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT"
	});

	public AggregateDataSharingResourceRS() {
		logger.info("initialize Aggregate Resource NO INJECTION");

		if (properties == null) {
			properties = new ApplicationProperties();
			properties.init("pic-sure-aggregate-resource");
		}

		threshold = properties.getTargetPicsureObfuscationThreshold();
		variance = properties.getTargetPicsureObfuscationVariance();
		randomSalt = properties.getTargetPicsureObfuscationSalt();

		headers = new Header[] {new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + properties.getTargetPicsureToken())};

	}

	@Inject
	public AggregateDataSharingResourceRS(ApplicationProperties applicationProperties) {
		this.properties = applicationProperties;
		logger.info("initialize Aggregate Resource Injected " + applicationProperties);

		if (properties == null) {
			properties = new ApplicationProperties();
			properties.init("pic-sure-aggregate-resource");
		}

		threshold = properties.getTargetPicsureObfuscationThreshold();
		variance = properties.getTargetPicsureObfuscationVariance();
		randomSalt = properties.getTargetPicsureObfuscationSalt();

		headers = new Header[] {new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + properties.getTargetPicsureToken())};
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
			QueryRequest chainRequest = new QueryRequest();
			if (infoRequest != null) {
				chainRequest.setQuery(infoRequest.getQuery());
				chainRequest.setResourceCredentials(infoRequest.getResourceCredentials());
				//set a default value of the existing uuid here (can override in properties file)
				chainRequest.setResourceUUID(infoRequest.getResourceUUID());
			}
			if(properties.getTargetResourceId() != null && !properties.getTargetResourceId().isEmpty()) {
				chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));
			}

			String payload = objectMapper.writeValueAsString(chainRequest);

			HttpResponse response = retrievePostResponse(composeURL(properties.getTargetPicsureUrl(), pathName), headers, payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				throwResponseError(response, properties.getTargetPicsureUrl());
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
		if (searchRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Object search = searchRequest.getQuery();
		if (search == null) {
			throw new ProtocolException((ProtocolException.MISSING_DATA));
		}

		String pathName = "/search";
		try {
			QueryRequest chainRequest = new QueryRequest();
			chainRequest.setQuery(searchRequest.getQuery());
			chainRequest.setResourceCredentials(searchRequest.getResourceCredentials());

			if(properties.getTargetResourceId() != null && !properties.getTargetResourceId().isEmpty()) {
				chainRequest.setResourceUUID(UUID.fromString(properties.getTargetResourceId()));
			} else {
				chainRequest.setResourceUUID(searchRequest.getResourceUUID());
			}

			String payload = objectMapper.writeValueAsString(chainRequest);

			HttpResponse response = retrievePostResponse(composeURL(properties.getTargetPicsureUrl(), pathName), headers, payload);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				throwResponseError(response, properties.getTargetPicsureUrl());
			}
			return readObjectFromResponse(response, SearchResults.class);
		} catch (IOException e) {
			// Note: this shouldn't ever happen
			logger.error("Error encoding search payload", e);
			throw new ApplicationException(
					"Error encoding search for resource with id " + searchRequest.getResourceUUID());
		}
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryJson) {
		logger.debug("Calling Aggregate Data Sharing Resource query()");
		throw new UnsupportedOperationException("Query is not implemented in this resource.  Please use query/sync");
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusQuery) {
		logger.debug("Calling Aggregate Data Sharing Resource queryStatus() for query {}", queryId);
		throw new UnsupportedOperationException(
				"Query status is not implemented in this resource.  Please use query/sync");
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
		logger.debug("Calling Aggregate Data Sharing Resource queryResult() for query {}", queryId);
		throw new UnsupportedOperationException(
				"Query result is not implemented in this resource.  Please use query/sync");
	}

	@POST
	@Path("/query/sync")
	@Override
	public Response querySync(QueryRequest queryRequest) {
		logger.debug("Calling Aggregate Data Sharing Resource querySync()");
		if (queryRequest == null || queryRequest.getQuery() == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}

		try {
			Object query = queryRequest.getQuery();
			UUID resourceUUID = queryRequest.getResourceUUID();

			JsonNode jsonNode = objectMapper.valueToTree(query);
			if (!jsonNode.has("expectedResultType")) {
				throw new ProtocolException(ProtocolException.MISSING_DATA);
			}
			String expectedResultType = jsonNode.get("expectedResultType").asText();

			if (!ALLOWED_RESULT_TYPES.contains(expectedResultType)) {
				logger.warn("Incorrect Result Type: " + expectedResultType);
				return Response.status(Response.Status.BAD_REQUEST).build();
			}

			HttpResponse response = getHttpResponse(queryRequest, resourceUUID, "/query/sync");

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

	private HttpResponse getHttpResponse(QueryRequest queryRequest, UUID resourceUUID, String pathName) throws JsonProcessingException {
		String targetPicsureUrl = properties.getTargetPicsureUrl();
		String queryString = objectMapper.writeValueAsString(queryRequest);
		return doHttpRequest(resourceUUID, targetPicsureUrl, queryString, pathName);
	}

	private HttpResponse doHttpRequest(UUID resourceUUID, String targetPicsureUrl, String queryString, String pathName) {
		String composedURL = composeURL(targetPicsureUrl, pathName);
		logger.debug("Aggregate Data Sharing Resource, sending query: " + queryString + ", to: " + composedURL);
		HttpResponse response = retrievePostResponse(composedURL, headers, queryString);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error("Not 200 status!");
			logger.error(
					composedURL + " calling resource with id " + resourceUUID + " did not return a 200: {} {} ",
					response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			throwResponseError(response, targetPicsureUrl);
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
	 * @param entityString The response from the backend that will be processed
	 * @param responseString The response that will be returned. Will return the passed entityString if
	 *                       no cases are matched.
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
				responseString = processContinuousCrossCounts(entityString, crossCountResponse);

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

		HttpResponse response = getHttpResponse(changeQueryToOpenCrossCount(queryRequest), queryRequest.getResourceUUID(), "/query/sync");
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

		LinkedHashMap<String, Object> rebuiltQuery = objectMapper.convertValue(includesStudyConsents, new TypeReference<>(){});
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
		LinkedHashMap<String, Object> linkedHashMap = objectMapper.convertValue(consentResults.getResults(), new TypeReference<>() {});
		Object phenotypes = linkedHashMap.get("phenotypes");
		LinkedHashMap<String, Object> phenotypesLinkedHashMap = objectMapper.convertValue(phenotypes, new TypeReference<>() {});

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

		QueryRequest studiesConsents = new QueryRequest();
		studiesConsents.setQuery("\\_studies_consents\\");
		return this.search(studiesConsents);
	}

	@Override
	@POST
	@Path("/query/format")
	public Response queryFormat(QueryRequest queryRequest) {
		if (queryRequest == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}
		Object search = queryRequest.getQuery();
		if (search == null) {
			throw new ProtocolException((ProtocolException.MISSING_DATA));
		}

		UUID resourceUUID = queryRequest.getResourceUUID();
		String pathName = "/query/format";

		try {
			String targetPicsureUrl = properties.getTargetPicsureUrl();
			String queryString = objectMapper.writeValueAsString(queryRequest);
			String composedURL = composeURL(targetPicsureUrl, pathName);
			HttpResponse response = retrievePostResponse(composeURL(properties.getTargetPicsureUrl(), pathName), headers, queryString);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("Not 200 status!");
				logger.error(
						composedURL + " calling resource with id " + resourceUUID + " did not return a 200: {} {} ",
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				throwResponseError(response, targetPicsureUrl);
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
		Map<String, String> crossCounts = objectMapper.readValue(entityString, new TypeReference<>(){});
		int requestVariance = generateVarianceWithCrossCounts(crossCounts);
		crossCounts = obfuscateCrossCounts(crossCounts, requestVariance);

		return crossCounts;
	}

	/**
	 * This method will appropriately process the obfuscation of the cross counts.
	 *
	 * @param crossCounts The cross counts
	 * @param requestVariance The variance for the request
	 * @return Map<String, String> The obfuscated cross counts
	 */
	private Map<String, String> obfuscateCrossCounts(Map<String, String> crossCounts, int requestVariance) {
		Set<String> obfuscatedKeys = new HashSet<>();
		if(crossCounts != null) {
			crossCounts.keySet().forEach(key -> {
				String crossCount = crossCounts.get(key);
				Optional<String> aggregatedCount = aggregateCount(crossCount);
				aggregatedCount.ifPresent((x) -> obfuscatedKeys.add(key));
				crossCounts.put(key, aggregatedCount.orElse(crossCount));
			});

			Set<String> obfuscatedParents = obfuscatedKeys.stream().flatMap(key -> {
				return generateParents(key);
			}).collect(Collectors.toSet());

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
		final List<Map.Entry<String, String>> entryList = new ArrayList(crossCounts.entrySet());

		// sort the entry set. By sorting the entry set first we can ensure that the variance is the same for each run.
		// This is to give us a random variance that is deterministic.
		entryList.sort(Map.Entry.comparingByKey());

		final StringBuffer crossCountsString = new StringBuffer();

		// build a string with lines like consent:1\n consent:2\n consent:3\n etc.
		entryList.forEach(entry -> crossCountsString.append(entry.getKey()).append(":").append(entry.getValue()).append("\n"));

		return generateRequestVariance(crossCountsString.toString());
	}

	protected String processContinuousCrossCounts(String continuousCrossCountResponse, String crossCountEntityString) throws IOException {
		logger.info("Processing continuous cross counts");
		logger.info("Cross count response: {} ", crossCountEntityString);
		logger.info("Continuous count response: {}", continuousCrossCountResponse);

		if (continuousCrossCountResponse == null || crossCountEntityString == null) {
			return null;
		}

		// convert continuousCrossCountResponse to a map
		Map<String, Map<String, Integer>> continuousCrossCounts = objectMapper.convertValue(continuousCrossCountResponse, new TypeReference<>() {});

		// I want to call the binning endpoint from the visualization service
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setResourceUUID(properties.getVisualizationResourceId());
		queryRequest.setQuery(continuousCrossCounts);

		// call the binning endpoint
		HttpResponse httpResponse = getHttpResponse(queryRequest, properties.getVisualizationResourceId(), "/format/continuous");

		HttpEntity entity = httpResponse.getEntity();
		String responseString = EntityUtils.toString(entity, "UTF-8");

		logger.info("Response from binning endpoint: {}", responseString);
		Map<String, Map<String, Integer>> binnedContinuousCrossCounts = objectMapper.convertValue(responseString, new TypeReference<>() {});

		Map<String, String> crossCounts = objectMapper.readValue(crossCountEntityString, new TypeReference<>(){});
		int generatedVariance = this.generateVarianceWithCrossCounts(crossCounts);

		String lessThanThresholdStr = "< " + this.threshold;
		String varianceStr = " \u00B1" + variance;
		boolean mustObfuscate = isCrossCountObfuscated(crossCounts, generatedVariance, lessThanThresholdStr, varianceStr);
		if (!mustObfuscate) {
			return objectMapper.writeValueAsString(binnedContinuousCrossCounts);
		}

		return objectMapper.writeValueAsString(binnedContinuousCrossCounts);
	}

	/**
	 * This method will process the categorical cross counts. It will first determine if the cross counts need to be
	 * obfuscated. This is done by checking if any of the cross_counts were obfuscated. If they were we need to obfuscate
	 * the categorical cross counts. If not we can just return the categorical entity string.
	 *
	 * @param categoricalEntityString The categorical entity string
	 * @param crossCountEntityString The cross count entity string
	 * @return String The processed categorical entity string
	 * @throws JsonProcessingException If there is an error processing the JSON
	 */
	protected String processCategoricalCrossCounts(String categoricalEntityString, String crossCountEntityString) throws JsonProcessingException {
		logger.info("Processing categorical cross counts");
		logger.info("Entity string: {}", categoricalEntityString);

		if (categoricalEntityString == null || crossCountEntityString == null) {
			return null;
		}

		Map<String, String> crossCounts = objectMapper.readValue(crossCountEntityString, new TypeReference<>(){});
		int generatedVariance = this.generateVarianceWithCrossCounts(crossCounts);

		String lessThanThresholdStr = "< " + this.threshold;
		String varianceStr = " \u00B1" + variance;

		// Based on the results of the cross counts, we need to determine if we need to obfuscate our categoricalCrossCount
		// If any of the cross counts are less than the threshold or have a variance, we need to obfuscate.
		boolean mustObfuscate = isCrossCountObfuscated(crossCounts, generatedVariance, lessThanThresholdStr, varianceStr);

		if (!mustObfuscate) {
			return categoricalEntityString;
		}

		Map<String, Map<String, Object>> categoricalCrossCount = objectMapper.readValue(categoricalEntityString, new TypeReference<>(){});
		if (categoricalCrossCount == null) {
			return categoricalEntityString;
		}

		// Now we need to obfuscate our return data. The only consideration is do we apply < threshold or variance
		categoricalCrossCount.forEach((key, value) -> {
			value.forEach((innerKey, innerValue) -> {
				Optional<String> aggregateCount = aggregateCountHelper(innerValue);
				if (aggregateCount.isPresent()) {
					value.put(innerKey, aggregateCount.get());
				} else {
					value.put(innerKey, randomize(innerValue.toString(), generatedVariance));
				}
			});
		});

		return objectMapper.writeValueAsString(categoricalCrossCount);
	}

	private boolean isCrossCountObfuscated(Map<String, String> crossCounts, int generatedVariance, String lessThanThresholdStr, String varianceStr) {
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

	/**
	 * This method will generate a random variance for the request based on the passed entityString. The variance
	 * will be between -variance and +variance. The variance will be generated by adding a random salt to the
	 * entityString and then taking the hashcode of the result. The variance will be the hashcode mod the
	 * variance * 2 + 1 - variance.
	 *
	 * @param entityString The entityString that will be used to generate the variance
	 * @return int The variance for the request
	 */
	private int generateRequestVariance(String entityString) {
		return Math.abs((entityString + randomSalt).hashCode()) % (variance * 2 + 1) - variance;
	}

	private String randomize(String crossCount, int requestVariance) {
		return Math.max((Integer.parseInt(crossCount) + requestVariance), threshold)  + " \u00B1" + variance;
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
	 * @param actualCount
	 * @return
	 */
	private Optional<String> aggregateCount(String actualCount) {
		try {
			int queryResult = Integer.parseInt(actualCount);
			if (queryResult > 0 && queryResult < threshold) {
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
