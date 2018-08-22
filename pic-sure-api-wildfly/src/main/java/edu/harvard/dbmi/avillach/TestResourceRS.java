package edu.harvard.dbmi.avillach;

import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.IResourceRS;

@Path("pic-sure")
public class TestResourceRS implements IResourceRS {

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@POST
	@Path("/query")
	@Override
	public QueryStatus query(QueryRequest queryJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(String queryId, QueryRequest statusRequest) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/result")
	@Override
	public Response queryResult(String queryId, QueryRequest resultRequest) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResourceInfo info(QueryRequest queryRequest) {
		// TODO Auto-generated method stub
		return null;
	}

}
