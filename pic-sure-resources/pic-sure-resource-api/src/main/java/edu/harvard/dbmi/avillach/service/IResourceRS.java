package edu.harvard.dbmi.avillach.service;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.*;
import io.swagger.v3.oas.annotations.Operation;

@Path("/pic-sure")
@Produces("application/json")
@Consumes("application/json")
public interface IResourceRS
{

	@POST
	@Path("/info")
	@Operation(hidden = true)
	default ResourceInfo info(QueryRequest queryRequest) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/search")
	@Operation(hidden = true)
	default SearchResults search(QueryRequest searchJson) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query")
	@Operation(hidden = true)
	default QueryStatus query(QueryRequest queryJson) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query/{resourceQueryId}/status")
	@Operation(hidden = true)
	default QueryStatus queryStatus(String queryId, QueryRequest statusRequest) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Operation(hidden = true)
	default Response queryResult(String queryId, QueryRequest resultRequest) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/query/sync")
	@Operation(hidden = true)
	default Response querySync(QueryRequest resultRequest) {
		throw new NotSupportedException("Query Sync is not implemented in this resource.  Please use query");
	}

	@POST
	@Path("/query/format")
	@Operation(hidden = true)
	default Response queryFormat(QueryRequest resultRequest) {
		throw new NotSupportedException("Query formatting is not implemented in this resource.");
	}

	@POST
	@Path("/search/values/")
	default PaginatedSearchResult<?> searchGenomicConceptValues(
			@QueryParam("genomicConceptPath") String genomicConceptPath,
			@QueryParam("query") String query,
			@QueryParam("page") int page,
			@QueryParam("size") int size
	) {
		throw new NotSupportedException();
	}

	@POST
	@Path("/bin/continuous")
	@Operation(hidden = true)
	default Response generateContinuousBin(QueryRequest continuousData) {
		throw new NotSupportedException();
	}

}
