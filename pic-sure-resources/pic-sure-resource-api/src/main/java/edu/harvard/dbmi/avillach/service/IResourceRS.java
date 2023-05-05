package edu.harvard.dbmi.avillach.service;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.*;

@Path("/pic-sure")
@Produces("application/json")
@Consumes("application/json")
public interface IResourceRS
{

	@POST
	@Path("/info")
	default ResourceInfo info(QueryRequest queryRequest) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/search")
	default SearchResults search(QueryRequest searchJson) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query")
	default QueryStatus query(QueryRequest queryJson) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	default QueryStatus queryStatus(String queryId, QueryRequest statusRequest) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	default Response queryResult(String queryId, QueryRequest resultRequest) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query/sync")
	default Response querySync(QueryRequest resultRequest) {
		throw new NotSupportedException("Query Sync is not implemented in this resource.  Please use query");
	}

	@POST
	@Path("/query/format")
	default Response queryFormat(QueryRequest resultRequest) {
		throw new NotSupportedException("Query formatting is not implemented in this resource.");
	}

	@GET
	@Path("/info/{conceptPath}/values/")
	default PaginatedSearchResult<?> infoConceptValues(
			@PathParam("conceptPath") String conceptPath,
			@QueryParam("query") String query,
			@QueryParam("page") Integer page,
			@QueryParam("size") Integer size
	) {
		throw new NotSupportedException();
	}
}
