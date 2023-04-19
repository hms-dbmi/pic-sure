package edu.harvard.hms.dbmi.avillach;

import static edu.harvard.dbmi.avillach.service.ResourceWebClient.QUERY_METADATA_FIELD;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.readObjectFromResponse;

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

import edu.harvard.dbmi.avillach.util.HttpClientUtil;
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

	private final Header[] headers;

	private static final String BEARER_STRING = "Bearer ";

	private final static ObjectMapper json = new ObjectMapper();
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final int threshold;
	private final int variance;

	private final String randomSalt;

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
			String composedURL = HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName);
			HttpResponse response = HttpClientUtil.retrievePostResponse(composedURL, headers, payload);
			if (HttpClientUtil.is2xx(response)) {
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
		return postRequest(searchRequest, "/search", SearchResults.class);
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryRequest) {
		logger.debug("Calling Aggregate Data Sharing Resource query()");
		checkQuery(queryRequest);
		return postRequest(queryRequest, "/query", QueryStatus.class);
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusRequest) {
		logger.debug("Calling Aggregate Data Sharing Resource queryStatus() for query {}", queryId);
		checkQuery(statusRequest);
		return postRequest(statusRequest, "/query/" + queryId + "/status", QueryStatus.class);
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
		logger.debug("Calling Aggregate Data Sharing Resource queryResult() for query {}", queryId);
		checkQuery(resultRequest);
		return postRequest(resultRequest, "/query/" + queryId + "/result", Response.class);
	}

	private <T> T postRequest(QueryRequest statusRequest, String pathName, Class<T> responseClazz) {
		try {
			QueryRequest chainRequest = createChainRequest(statusRequest);

			String payload = objectMapper.writeValueAsString(chainRequest);
			String composedURL = HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName);
			HttpResponse response = HttpClientUtil.retrievePostResponse(composedURL, headers, payload);
			if (HttpClientUtil.is2xx(response)) {
				logger.error("{}{} did not return a 200: {} {} ", properties.getTargetPicsureUrl(), pathName,
					response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				HttpClientUtil.throwResponseError(response, properties.getTargetPicsureUrl());
			}
			return readObjectFromResponse(response, responseClazz);
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

			JsonNode jsonNode = json.valueToTree(query);
			if (!jsonNode.has("expectedResultType")) {
				throw new ProtocolException(ProtocolException.MISSING_DATA);
			}
			String expectedResultType = jsonNode.get("expectedResultType").asText();

			Set<String> allowedResultTypes =
				Set.of("COUNT", "CROSS_COUNT", "INFO_COLUMN_LISTING", "OBSERVATION_COUNT", "OBSERVATION_CROSS_COUNT");
			if (!allowedResultTypes.contains(expectedResultType)) {
				logger.warn("Incorrect Result Type: " + expectedResultType);
				return Response.status(Response.Status.BAD_REQUEST).build();
			}

			String payload = json.writeValueAsString(queryRequest);
			String pathName = "/query/sync";
			String composedURL = HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName);
			logger.debug("Aggregate Data Sharing Resource, sending query: " + payload + ", to: " + composedURL);
			HttpResponse response = HttpClientUtil.retrievePostResponse(composedURL, headers, payload);
			if (HttpClientUtil.is2xx(response)) {
				logger.error(
						"{} calling resource with id {} did not return a 200: {} {} ", composedURL, resourceUUID,
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				HttpClientUtil.throwResponseError(response, properties.getTargetPicsureUrl());
			}


			HttpEntity entity = response.getEntity();
			String entityString = EntityUtils.toString(entity, "UTF-8");
			String responseString = entityString;

			if("COUNT".equals(expectedResultType)) {
				responseString = aggregateCount(entityString).orElse(entityString);
			} else if("CROSS_COUNT".equals(expectedResultType)) {
				Map<String, String> crossCounts = processCrossCounts(entityString);
				responseString = objectMapper.writeValueAsString(crossCounts);
			}
			
			//propagate any metadata from the back end (e.g., resultId)
			if(response.containsHeader(QUERY_METADATA_FIELD)) {
            	Header metadataHeader = ((Header[])response.getHeaders(QUERY_METADATA_FIELD))[0];
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

	@Override
	@POST
	@Path("/query/format")
	public Response queryFormat(QueryRequest queryRequest) {
		checkQuery(queryRequest);

		UUID resourceUUID = queryRequest.getResourceUUID();
		String pathName = "/query/format";

		try {
			String queryString = json.writeValueAsString(queryRequest);
			String composedURL = HttpClientUtil.composeURL(properties.getTargetPicsureUrl(), pathName);
			HttpResponse response = HttpClientUtil.retrievePostResponse(composedURL, headers, queryString);
			if (HttpClientUtil.is2xx(response)) {
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
		Map<String, String> crossCounts = objectMapper.readValue(entityString, new TypeReference<>(){});
		final List<Map.Entry<String, String>> entryList = new ArrayList<>(crossCounts.entrySet());
		entryList.sort(Map.Entry.comparingByKey());
		final StringBuffer crossCountsString = new StringBuffer();
		entryList.forEach(entry -> crossCountsString.append(entry.getKey()).append(":").append(entry.getValue()).append("\n"));
		int requestVariance = generateRequestVariance(crossCountsString.toString());
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

	private QueryRequest createChainRequest(QueryRequest queryRequest) {
		QueryRequest chainRequest = new QueryRequest();
		chainRequest.setQuery(queryRequest.getQuery());
		chainRequest.setResourceCredentials(queryRequest.getResourceCredentials());

		if(properties.getTargetResourceId() != null && !properties.getTargetResourceId().isEmpty()) {
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

}
