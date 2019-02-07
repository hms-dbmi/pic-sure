package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.util.*;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import edu.harvard.dbmi.avillach.util.exception.ResourceInterfaceException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;

import edu.harvard.dbmi.avillach.service.IResourceRS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static edu.harvard.dbmi.avillach.util.HttpClientUtil.*;


@Path("/hsapi")
@Produces("application/json")
@Consumes("application/json")
public class HSAPIResourceRS implements IResourceRS
{
	private final static ObjectMapper json = new ObjectMapper();
	private ResourceInfo HSAPIresourceInfo = new ResourceInfo();
	private Header[] headers;
	private Logger logger = LoggerFactory.getLogger(this.getClass());


	public HSAPIResourceRS() {
		//This only needs to be done once
		List<QueryFormat> queryFormats = new ArrayList<>();

		// /hsapi/resource/
		QueryFormat resource = new QueryFormat().setName("Resource List").setDescription("List existing resources");
		Map<String, Object> specification = new HashMap<>();
		specification.put("entity", "The type of entity you wish to retrieve or explore - e.g. resource or user");
		specification.put("page", "Optional - A page number within the paginated result set");
		resource.setSpecification(specification);

		Map<String, Object> example = new HashMap<>();
		example.put("entity", "resource");
		List<Map<String, Object>> examples = new ArrayList<>();
		examples.add(example);

		Map<String, Object> example2 = new HashMap<>();
		example2.put("entity", "resource");
		example2.put("page", "2");
		examples.add(example2);

		resource.setExamples(examples);
		queryFormats.add(resource);

		// /hsapi/resource/{id}/files/
		QueryFormat files = new QueryFormat().setName("File List").setDescription("Get a listing of files within a resource");
		specification.put("entity", "The type of entity you wish to retrieve or explore - e.g. resource or user");
		specification.put("id", "The id of the specific entity to retrieve or explore");
		specification.put("subentity", "A type of entity within the main entity - e.g. file (under resource)");
		specification.put("page", "Optional - A page number within the paginated result set");
		files.setSpecification(specification);

		example = new HashMap<>();
		example.put("entity", "resource");
		example.put("id", "a1b23c");
		example.put("subentity", "files");
		examples = new ArrayList<>();
		examples.add(example);

		example2 = new HashMap<>();
		example2.put("entity", "resource");
		example2.put("id", "a1b23c");
		example2.put("subentity", "files");
		example2.put("page", "2");
		examples.add(example2);
		files.setExamples(examples);
		queryFormats.add(files);

		// /hsapi/resource/{id}/files/{pathname}/
		QueryFormat filePath = new QueryFormat().setName("Get File").setDescription("Retrieve a resource file");

		specification.put("entity", "The type of entity you wish to retrieve or explore - e.g. resource or user");
		specification.put("id", "The id of the specific entity to retrieve or explore");
		specification.put("subentity", "A type of entity within the main entity - e.g. file (under resource)");
		specification.put("pathname", "The name or path of the specific subentity you are looking for");
		files.setSpecification(specification);

		example = new HashMap<>();
		example.put("entity", "resource");
		example.put("id", "a1b23c");
		example.put("subentity", "files");
		example.put("pathname", "abc.csv");
		examples = new ArrayList<>();
		examples.add(example);
		filePath.setExamples(examples);
		queryFormats.add(filePath);

		HSAPIresourceInfo.setQueryFormats(queryFormats);
	}

	@GET
	@Path("/status")
	public Response status() {
		return Response.ok().build();
	}

	@POST
	@Path("/info")
	@Override
	public ResourceInfo info(QueryRequest queryRequest) {
		logger.debug("Calling HSAPI Resource info()");
		HSAPIresourceInfo.setName("HSAPI Resource : " + getTargetURL());
		return HSAPIresourceInfo;
	}

	private String getTargetURL() {
		// TODO Auto-generated method stub
		return null;
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		logger.debug("Calling HSAPI Resource search()");
		throw new UnsupportedOperationException("Search is not implemented for this resource");
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryJson) {
		logger.debug("Calling HSAPI Resource query()");
		throw new UnsupportedOperationException("Query is not implemented in this resource.  Please use query/sync");
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") String queryId, QueryRequest statusQuery) {
		logger.debug("calling HSAPI Resource queryStatus() for query {}", queryId);
		throw new UnsupportedOperationException("Query status is not implemented in this resource.  Please use query/sync");

	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public Response queryResult(@PathParam("resourceQueryId") String queryId, QueryRequest statusQuery) {
		logger.debug("calling HSAPI Resource queryResult() for query {}", queryId);
		throw new UnsupportedOperationException("Query result is not implemented in this resource.  Please use query/sync");
	}

	@POST
	@Path("/query/sync")
	@Override
	public Response querySync(QueryRequest resultRequest) {
		logger.debug("calling HSAPI Resource querySync()");
        if (resultRequest == null){
            throw new ProtocolException(ProtocolException.MISSING_DATA);
        }

        if (getTargetURL() == null){
            throw new ApplicationException(ApplicationException.MISSING_TARGET_URL);
        }
		Object queryObject = resultRequest.getQuery();
		if (queryObject == null) {
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}

		String path = buildPath(resultRequest);

		HttpResponse response = retrieveGetResponse(path, headers);
		if (response.getStatusLine().getStatusCode() != 200) {
			logger.error(getTargetURL() + " did not return a 200: {} {}", response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
			throwResponseError(response, getTargetURL());
		}
		try {
			return Response.ok(response.getEntity().getContent()).build();
		} catch (IOException e){
			throw new ResourceInterfaceException("Unable to read the resource response: " + e.getMessage());
		}
	}

	private String buildPath (QueryRequest request){
		JsonNode node = json.valueToTree(request.getQuery());

		if (!node.has("entity")){
			throw new ProtocolException("Entity required");
		}
		String path = node.get("entity").asText();

		//Alert user if their request is ill-formed
		if (node.has("id")) {
			path += "/" + node.get("id").asText();
		}

		if (node.has("subentity")) {
			if (!node.has("id")) {
				throw new ProtocolException("Cannot have subentity without an id");
			}
			path += "/" + node.get("subentity").asText();
		}

		if (node.has("pathname")){
			if (!node.has("subentity")) {
				throw new ProtocolException("Cannot have pathname without subentity");
			}
			path += "/" + node.get("pathname").asText();
		}

		if (node.has("page")){
			if (node.has("pathname") || (node.has("id") && !node.has("subentity"))){
				throw new ProtocolException("Page can only be included at the end of entities or subentities");
			}
			String query = "/?page=" + node.get("page").asText();
			return composeURL(getTargetURL(), path, query);
		}

		return composeURL(getTargetURL(), path);
	}

}
