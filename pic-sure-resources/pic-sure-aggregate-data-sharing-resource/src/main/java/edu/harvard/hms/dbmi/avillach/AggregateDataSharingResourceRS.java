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

	private final static ObjectMapper json = new ObjectMapper();
	private Logger logger = LoggerFactory.getLogger(this.getClass());

	private final int threshold;
	private final int variance;

	private final String randomSalt;

	public static final List<String> ALLOWED_RESULT_TYPES = Arrays.asList(new String [] {
		"COUNT", "CROSS_COUNT", "INFO_COLUMN_LISTING", "OBSERVATION_COUNT", "OBSERVATION_CROSS_COUNT"
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

			JsonNode jsonNode = json.valueToTree(query);
			if (!jsonNode.has("expectedResultType")) {
				throw new ProtocolException(ProtocolException.MISSING_DATA);
			}
			String expectedResultType = jsonNode.get("expectedResultType").asText();

			if (! ALLOWED_RESULT_TYPES.contains(expectedResultType)) {
				logger.warn("Incorrect Result Type: " + expectedResultType);
				return Response.status(Response.Status.BAD_REQUEST).build();
			}

			String targetPicsureUrl = properties.getTargetPicsureUrl();
			String queryString = json.writeValueAsString(queryRequest);
			String pathName = "/query/sync";
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


			HttpEntity entity = response.getEntity();
			String entityString = EntityUtils.toString(entity, "UTF-8");
			String responseString = entityString;

			if(expectedResultType.equals("COUNT")) {
				responseString = aggregateCount(entityString).orElse(entityString);
			} else if(expectedResultType.equals("CROSS_COUNT")) {
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
			String queryString = json.writeValueAsString(queryRequest);
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
		final List<Map.Entry<String, String>> entryList = new ArrayList(crossCounts.entrySet());
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
