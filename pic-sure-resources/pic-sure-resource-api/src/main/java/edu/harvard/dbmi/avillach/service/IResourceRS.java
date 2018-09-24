package edu.harvard.dbmi.avillach.service;

import java.util.Map;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.*;

@Path("/pic-sure")
@Produces("application/json")
@Consumes("application/json")
public interface IResourceRS
{

	@GET
	@Path("/info")
	public ResourceInfo info(QueryRequest queryRequest);

	@POST
	@Path("/search")
	public SearchResults search(QueryRequest searchJson);

	@POST
	@Path("/query")
	public QueryStatus query(QueryRequest queryJson);

	@POST
	@Path("/query/{resourceQueryId}/status")
	public QueryStatus queryStatus(String queryId, QueryRequest statusRequest);

	@POST
	@Path("/query/{resourceQueryId}/result")
	public Response queryResult(String queryId, QueryRequest resultRequest);

	@POST
	@Path("/query/sync")
	public Response querySync(QueryRequest resultRequest);

}
