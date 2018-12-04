package edu.harvard.hms.dbmi.avillach.pheno;

import java.io.IOException;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.pheno.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.pheno.data.AsyncResult;
import edu.harvard.hms.dbmi.avillach.pheno.data.Query;
import edu.harvard.hms.dbmi.avillach.picsure.hpds.exception.ValidationException;

@Path("query")
public class QueryRS {
	
	@Autowired
	QueryService queryService;
	
	@POST
	@Produces(MediaType.APPLICATION_JSON_VALUE)
	public Response runQuery(Query query) {
		try {
			return Response.ok(queryService.runQuery(query)).build();
		}catch(ValidationException e) {
			return Response.status(400).entity(e.getResult()).build();
		}
	}
	
	@GET
	@Path("{queryId}/status")
	@Produces(MediaType.APPLICATION_JSON_VALUE)
	public Response getStatusFor(@PathParam("queryId") String queryId) {
		return Response.ok(queryService.getStatusFor(queryId)).build();
	}
	
	@GET
	@Path("{queryId}/result")
	@Produces(MediaType.TEXT_PLAIN_VALUE)
	public Response getResultFor(@PathParam("queryId") String queryId) {
		AsyncResult result = queryService.getResultFor(queryId);
		if(result.status==AsyncResult.Status.SUCCESS) {
			result.stream.open();
			return Response.ok(result.stream).build();			
		}else {
			return Response.status(400).entity("Status : " + result.status.name()).build();
		}

	}
	
	@GET
	@Path("dictionary")
	@Produces(MediaType.APPLICATION_JSON_VALUE)
	public Response getDataDictionary() {
		return Response.ok(queryService.getDataDictionary()).build();
	}
	

	@POST
	@Path("/count")
	public Response querySync(Query resultRequest) throws JsonParseException, JsonMappingException, JsonProcessingException, IOException {
		if(Crypto.hasKey()){
			return Response.ok(new CountProcessor().runCounts(resultRequest)).build();
		} else {
			return Response.status(403).entity("Resource is locked").build();
		}
	}
}
