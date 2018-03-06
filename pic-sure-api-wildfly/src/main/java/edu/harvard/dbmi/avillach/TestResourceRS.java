package edu.harvard.dbmi.avillach;

import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.service.IResourceRS;

@Path("pic-sure")
public class TestResourceRS implements IResourceRS {
	
	@GET
	@Path("/info")
	@Override
	public ResourceInfo info() {
		return new ResourceInfo();
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(String searchJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@POST
	@Path("/query")
	@Override
	public QueryResults query(String queryJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/status)")
	@Override
	public QueryStatus queryStatus(UUID queryId) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/result")
	@Override
	public QueryResults queryResult(UUID queryId) {
		// TODO Auto-generated method stub
		return null;
	}

}
