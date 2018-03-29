package edu.harvard.dbmi.avillach;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

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
	public QueryResults query(QueryRequest queryJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/status")
	@Override
	public QueryStatus queryStatus(String queryId, Map<String, String> resourceCredentials) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/result")
	@Override
	public QueryResults queryResult(String queryId, Map<String, String> resourceCredentials, String accept) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResourceInfo info(Map<String, String> resourceCredentials) {
		// TODO Auto-generated method stub
		return null;
	}

}
