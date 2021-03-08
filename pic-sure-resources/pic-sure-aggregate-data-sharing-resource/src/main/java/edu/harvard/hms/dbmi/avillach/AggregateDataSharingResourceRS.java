package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.util.*;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.annotation.Resource;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.PicsureQueryException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.apache.http.HttpResponse;
import org.apache.http.HttpEntity;
import org.apache.http.util.EntityUtils;
import org.apache.http.client.entity.EntityBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static edu.harvard.dbmi.avillach.util.HttpClientUtil.*;

@Path("/aggregate-data-sharing")
@Produces("application/json")
@Consumes("application/json")
public class AggregateDataSharingResourceRS implements IResourceRS {

	@Inject
	private ApplicationProperties properties;
	
	private Header[] headers;

	private static final String BEARER_STRING = "Bearer ";

	private final static ObjectMapper json = new ObjectMapper();
	private Logger logger = LoggerFactory.getLogger(this.getClass());

	public enum ResultType {
		COUNT, CROSS_COUNT, INFO_COLUMN_LISTING
	}

	public AggregateDataSharingResourceRS() {
		logger.info("initialize Aggregate Resource NO INJECTION");

		if (properties == null) {
			properties = new ApplicationProperties();
			properties.init("pic-sure-aggregate-resource");
		}
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
	public ResourceInfo info(QueryRequest queryRequest) {
		logger.debug("Calling Aggregate Data Sharing Resource info()");
		return new ResourceInfo();
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		logger.debug("Calling Aggregate Data Sharing Resource search()");
		return new SearchResults();
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
			
			logger.debug("result type " + expectedResultType + ".");
			logger.debug("allowed types " + Arrays.deepToString(ResultType.values()));
			
			if (! (Arrays.asList(ResultType.values()).contains(expectedResultType))) {
				logger.warn("Incorrect Result Type: " + expectedResultType);
//				return Response.status(Response.Status.BAD_REQUEST).build();
			}

			logger.warn("XX");
			String targetPicsureUrl = properties.getTargetPicsureUrl();
			logger.warn("targetPicsureUrl: " + targetPicsureUrl);
			String targetPicsureObfuscationThreshold = properties.getTargetPicsureObfuscationThreshold();
			logger.warn("query: " + queryRequest.toString());
			String queryString = json.writeValueAsString(queryRequest);
			logger.warn("queryString " + queryString);
			String pathName = "/query/sync";
			logger.warn("XX");
			String composedURL = composeURL(targetPicsureUrl, pathName);
			logger.warn("XX");
			logger.debug("Aggregate Data Sharing Resource, sending query: " + queryString + ", to: " + composedURL);
			logger.warn("XX");
			HttpResponse response = retrievePostResponse(composedURL, headers, queryString);
			if (response.getStatusLine().getStatusCode() != 200) {
				logger.error("Not 200 status!");
				logger.error(
						composedURL + " calling resource with id " + resourceUUID + " did not return a 200: {} {} ",
						response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
				throwResponseError(response, targetPicsureUrl);
			}
			logger.warn("YY");
			int threshold = Integer.parseInt(targetPicsureObfuscationThreshold);
			logger.warn("YY");
			HttpEntity entity = response.getEntity();
			logger.warn("YY");
			String entityString = EntityUtils.toString(entity, "UTF-8");
			logger.warn("YY");
			String responseString = entityString;
			int queryResult = Integer.parseInt(entityString);
			if (queryResult < threshold) {
				responseString = "< " + targetPicsureObfuscationThreshold;
			}
			logger.warn("ZZ");
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
}
