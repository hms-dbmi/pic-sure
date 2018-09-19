package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;

import edu.harvard.dbmi.avillach.service.IResourceRS;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;


@Path("/gnome-i2b2-count")
@Produces("application/json")
@Consumes("application/json")
public class GnomeI2B2CountResourceRS implements IResourceRS
{
	private static final String TARGET_GNOME_URL = System.getenv("TARGET_GNOME_URL");
	private static final String TARGET_I2B2_URL = System.getenv("TARGET_I2B2_URL");
	private static final String TARGET_PICSURE_URL = System.getenv("TARGET_PICSURE_URL");
	private static final String PICSURE_2_TOKEN = System.getenv("PICSURE_2_TOKEN");
	public static final String GNOME_BEARER_TOKEN_KEY = "GNOME_BEARER_TOKEN";
	public static final String I2B2_BEARER_TOKEN_KEY = "I2B2_BEARER_TOKEN";
	public static final String GNOME = "gnome";
	public static final String I2B2 = "i2b2";
	public static final String MISSING_REQUEST_DATA_MESSAGE = "Missing query request data";
	public static final String MISSING_CREDENTIALS_MESSAGE = "Missing credentials";
	private static final String BEARER_STRING = "Bearer ";
	public static final String GNOME_LABEL = "Sample ID";
	public static final String I2B2_LABEL = System.getenv("I2B2_LABEL");

	private static Header[] picsure2headers = {new BasicHeader(HttpHeaders.AUTHORIZATION, BEARER_STRING + PICSURE_2_TOKEN)};
	private final static ObjectMapper json = new ObjectMapper();
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	private JsonNode jsn;


	public GnomeI2B2CountResourceRS() {
		if(TARGET_GNOME_URL == null)
			throw new RuntimeException("TARGET_GNOME_URL environment variable must be set.");
		if(TARGET_I2B2_URL == null)
			throw new RuntimeException("TARGET_I2B2_URL environment variable must be set.");
		if(TARGET_PICSURE_URL == null)
			throw new RuntimeException("TARGET_PICSURE_URL environment variable must be set.");
		if(PICSURE_2_TOKEN == null)
			throw new RuntimeException("PICSURE_2_TOKEN environment variable must be set.");
		if(I2B2_LABEL == null)
			throw new RuntimeException("I2B2_LABEL environment variable must be set.");
	}
	
	@GET
	@Path("/status")
	public Response status() {
		return Response.ok().build();
	}

	@POST
	@Path("/info")
	@Override
	public ResourceInfo info(QueryRequest resourceCredentials) {
		//TODO Not sure what to do with this method - there are kind of two resources right?
		logger.debug("Calling Gnome-I2B2-Count Resource info()");
		return new ResourceInfo();
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		//TODO Will this method be used?
		logger.debug("Calling Gnome-I2B2-Count Resource search()");
		return new SearchResults();
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryJson) {
		logger.debug("Calling Gnome-I2B2-Count Resource query()");
		if (queryJson == null) {
			throw new ProtocolException(MISSING_REQUEST_DATA_MESSAGE);
		}
		Map<String, String> resourceCredentials = queryJson.getResourceCredentials();
		if (resourceCredentials == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		String token = resourceCredentials.get(GNOME_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for gNOME");
		}

		Object queryObject = queryJson.getQuery();
		if (queryObject == null) {
			throw new ProtocolException((MISSING_REQUEST_DATA_MESSAGE));
		}

		QueryStatus result = new QueryStatus();
		HashMap<String, String> resultIds = new HashMap<>();
		//TODO Might this throw an error I need to deal with?
		JsonNode queryNode = json.valueToTree(queryObject);
		String queryString = null;

		JsonNode query = queryNode.get(GNOME);
		if (query == null){
			throw new ProtocolException((MISSING_REQUEST_DATA_MESSAGE  + " for gNOME"));
		} else {
			queryString = query.toString();
		}

		String pathName = "/queryService/runQuery";
		HttpResponse response = retrievePostResponse(TARGET_GNOME_URL + pathName, createAuthorizationHeader(token), queryString);
		if (response.getStatusLine().getStatusCode() != 200) {
			throwResponseError(response, TARGET_GNOME_URL);
		}
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			JsonNode responseNode = json.readTree(responseBody);
			String resultId = responseNode.get("resultId").asText();
			resultIds.put(GNOME, resultId);
			//Gnome has no status
//			result.setStatus(mapStatus(responseNode.get("status").asText()));
		} catch (IOException e){
			throw new ApplicationException(e);
		}

		query = queryNode.get(I2B2);
		if (query == null){
			throw new ProtocolException((MISSING_REQUEST_DATA_MESSAGE + " for I2B2"));
		} else {
			queryString = query.toString();
		}

		token = resourceCredentials.get(I2B2_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for I2B2");
		}
		pathName = "/queryService/runQuery";
		response = retrievePostResponse(TARGET_I2B2_URL + pathName, createAuthorizationHeader(token), queryString);
		if (response.getStatusLine().getStatusCode() != 200) {
			throwResponseError(response, TARGET_I2B2_URL);
		}
		try {
			String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
			JsonNode responseNode = json.readTree(responseBody);
			String resultId = responseNode.get("resultId").asText();
			resultIds.put(I2B2, resultId);
			/*if (!PicSureStatus.ERROR.equals(result.getStatus())){
				PicSureStatus status = mapStatus(responseNode.get("status").asText());
				if (PicSureStatus.PENDING.equals(status) || PicSureStatus.PENDING.equals(result.getStatus())){
					result.setStatus(PicSureStatus.PENDING);
				} else if (PicSureStatus.AVAILABLE.equals(status) || PicSureStatus.AVAILABLE.equals(result.getStatus())) {
					result.setStatus(PicSureStatus.AVAILABLE);
				}
			}*/

		} catch (IOException e){
			throw new ApplicationException(e);
		}
		//TODO Should I call queryStatus to find out (don't have id yet)
		result.setStatus(PicSureStatus.PENDING);
		result.setResultMetadata(SerializationUtils.serialize(resultIds));
		return result;
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusRequest) {
		logger.debug("calling Gnome-I2B2-Count Resource queryStatus() for query {}", queryId);
		if (statusRequest == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		Map<String, String> resourceCredentials = statusRequest.getResourceCredentials();
		if (resourceCredentials == null){
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for gNOME");
		}
		String token = resourceCredentials.get(GNOME_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for gNOME");
		}

		QueryStatus statusResponse = new QueryStatus();
		statusResponse.setPicsureResultId(UUID.fromString(queryId));

		HashMap<String, String> queryIds = getMetadata(queryId);
		String gnomeId = queryIds.get(GNOME);
		if (gnomeId == null){
			throw new ApplicationException("Unable to fetch Gnome query");
		}

		String pathName = "/resultService/resultStatus/" + gnomeId;
		HttpResponse response = retrieveGetResponse(TARGET_GNOME_URL + pathName, createAuthorizationHeader(token));
		if (response.getStatusLine().getStatusCode() != 200) {
			throwResponseError(response, TARGET_GNOME_URL);
		}
		try {
			JsonNode responseNode = json.readTree(response.getEntity().getContent());
			String resourceStatus = responseNode.get("status").asText();
			statusResponse.setStatus(mapStatus(resourceStatus));

			token = resourceCredentials.get(I2B2_BEARER_TOKEN_KEY);
			if (token == null) {
				throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for I2B2");
			}
			String i2b2Id = queryIds.get(I2B2);
			if (i2b2Id == null){
				throw new ApplicationException("Unable to fetch I2B2 query");
			}
			pathName = "/resultService/resultStatus/" + i2b2Id;
			response = retrieveGetResponse(TARGET_I2B2_URL + pathName, createAuthorizationHeader(token));
			if (response.getStatusLine().getStatusCode() != 200) {
				throwResponseError(response, TARGET_I2B2_URL);
			}
			responseNode = json.readTree(response.getEntity().getContent());
			//TODO This seems like possibly not the best logic
			if (!PicSureStatus.ERROR.equals(statusResponse.getStatus())){
				PicSureStatus picsureStatus = mapStatus(responseNode.get("status").asText());
				if (PicSureStatus.ERROR.equals(picsureStatus)){
					statusResponse.setStatus(PicSureStatus.ERROR);
				} else if (PicSureStatus.PENDING.equals(picsureStatus) || PicSureStatus.PENDING.equals(statusResponse.getStatus())){
					statusResponse.setStatus(PicSureStatus.PENDING);
				} else if (PicSureStatus.AVAILABLE.equals(picsureStatus) || PicSureStatus.AVAILABLE.equals(statusResponse.getStatus())) {
					statusResponse.setStatus(PicSureStatus.AVAILABLE);
				}
			}

		} catch (JsonProcessingException e){
			throw new ApplicationException("Unable to encode resource credentials");
		} catch (IOException e){
			throw new ApplicationException("Unable to read query status");
		}


		return statusResponse;
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest resultRequest) {
		logger.debug("calling Gnome-I2B2-Count Resource queryResult() for query {}", queryId);
		if (resultRequest == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE);
		}
		Map<String, String> resourceCredentials = resultRequest.getResourceCredentials();
		if (resourceCredentials == null){
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for gNOME");
		}
		String token = resourceCredentials.get(GNOME_BEARER_TOKEN_KEY);
		if (token == null) {
			throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for gNOME");
		}

		HashMap<String, String> queryIds = getMetadata(queryId);
		String gnomeId = queryIds.get(GNOME);
		if (gnomeId == null){
			throw new ApplicationException("Unable to fetch Gnome query");
		}

		String pathName = "/resultService/result/" + gnomeId + "/JSON";
		HttpResponse response = retrieveGetResponse(TARGET_GNOME_URL + pathName, createAuthorizationHeader(token));
		if (response.getStatusLine().getStatusCode() != 200) {
			throwResponseError(response, TARGET_GNOME_URL);
		}

		int responseCount = 0;

		try {
			/* The response should look something like this:
			{
			    "data":
      				[
				        [{"Project name":"Obesity"},
          				{"Sample ID":"abc11de1_2f2g_333a_bb4b_55cde5555fg5"},
          				{"Sample group":"case"},...
          				],
          				[{"Project name":"Obesity"},...
          				]...
					]
			}
			 */
			JsonNode gnomeResponse = json.readTree(response.getEntity().getContent());
			ArrayNode gnomeData = (ArrayNode) gnomeResponse.get("data");

			token = resourceCredentials.get(I2B2_BEARER_TOKEN_KEY);
			if (token == null) {
				throw new NotAuthorizedException(MISSING_CREDENTIALS_MESSAGE + " for I2B2");
			}
			String i2b2Id = queryIds.get(I2B2);
			if (i2b2Id == null){
				throw new ApplicationException("Unable to fetch I2B2 query");
			}
			pathName = "/resultService/result/" + i2b2Id + "/JSON";
			response = retrieveGetResponse(TARGET_I2B2_URL + pathName, createAuthorizationHeader(token));
			if (response.getStatusLine().getStatusCode() != 200) {
				throwResponseError(response, TARGET_I2B2_URL);
			}

			/* The response should look something like this:
			{ "data" :
   				[
     				[{"Patient Id":"123456"},{"BCH_IMPORT":"1A2C34-5BD5-44A6-B509-B99888AAFGB9"}],
     				[{"Patient Id":"123457"}], ...
     			]
     		}
			 */

			JsonNode i2b2Response = json.readTree(response.getEntity().getContent());
			ArrayNode i2b2Data = (ArrayNode) i2b2Response.get("data");

			//Find matching ids
			Set<String> gnomepatientIds = new HashSet<>();
			Set<String> i2b2patientIds = new HashSet<>();
			for (JsonNode jsn : gnomeData){
				gnomepatientIds.addAll(getFields(jsn, GNOME_LABEL));
			}
			for (JsonNode jsn : i2b2Data){
				i2b2patientIds.addAll(getFields(jsn, I2B2_LABEL));
			}

			//Make sure ids are in the same format so they match appropriately
			gnomepatientIds = gnomepatientIds.stream().map(s -> cleanString(s)).distinct().collect(Collectors.toSet());
			i2b2patientIds = i2b2patientIds.stream().map(s -> cleanString(s)).distinct().collect(Collectors.toSet());

			//Keep the patient ids that are on both
			gnomepatientIds.retainAll(i2b2patientIds);
			responseCount = gnomepatientIds.size();

		} catch (JsonProcessingException e){
			throw new ApplicationException("Unable to encode resource credentials");
		} catch (IOException e){
			throw new ApplicationException("Unable to read query status");
		}

		return Response.ok(responseCount).build();
	}

	private PicSureStatus mapStatus(String resourceStatus){
		switch (resourceStatus) {
			case "RUNNING":
				return PicSureStatus.PENDING;
			case "AVAILABLE":
				return PicSureStatus.AVAILABLE;
			case "ERROR":
				return PicSureStatus.ERROR;
			default:
				return null;
		}

	}

	//Standardizes a string
	private String cleanString(String str){
		return str.toLowerCase().replaceAll("_", "-").replaceAll(" ", "_");
	}

	//Returns the value of every node whose key matches field when both key and field are cleaned
	private List<String> getFields(JsonNode jsn, String field){

		String cleanedField = cleanString(field);
		return StreamSupport.stream(jsn.spliterator(), false)
				.flatMap(x -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(x.fields(), Spliterator.NONNULL), false))
				.filter(s -> cleanedField.equals(cleanString(s.getKey())))
				.map(s -> s.getValue().asText())
				.collect(Collectors.toList());
	}

	private Header[] createAuthorizationHeader(String token){
		Header authorizationHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, ResourceWebClient.BEARER_STRING + token);
		Header[] headers = {authorizationHeader};
		return headers;
	}

	private HashMap<String, String> getMetadata(String queryId){
		String pathName = "/query/" + queryId + "/metadata";
		HttpResponse response = retrieveGetResponse(TARGET_PICSURE_URL + pathName, picsure2headers);
		QueryStatus status = readObjectFromResponse(response, QueryStatus.class);
		return SerializationUtils.deserialize(status.getResultMetadata());
	}

}
