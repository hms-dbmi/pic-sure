package edu.harvard.dbmi.avillach.service;

import java.sql.Date;
import java.util.*;

import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.PicsureQueryException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.Response;

/**
 * Service handling business logic for queries to resources
 */
public class PicsureQueryService {

	private Logger logger = LoggerFactory.getLogger(PicsureQueryService.class);

	@Inject
	ResourceRepository resourceRepo;

	@Inject
	QueryRepository queryRepo;

	@Inject
	ResourceWebClient resourceWebClient;

	/**
	 * Executes a query on a PIC-SURE resource and creates a QueryResults object in the
	 * database for the query.
	 *
	 * @param dataQueryRequest - - {@link QueryRequest} containing resource specific credentials object
	 *                       and resource specific query (could be a string or a json object)
	 * @return {@link QueryStatus}
	 */
	@Transactional
	public QueryStatus query(QueryRequest dataQueryRequest) {
		UUID resourceId = dataQueryRequest.getResourceUUID();
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			//TODO Create custom exception
			throw new PicsureQueryException("No resource with id " + resourceId.toString() + " exists");
		}
		if (resource.getTargetURL() == null){
			throw new ApplicationException("Resource is missing target URL");
		}
		if (dataQueryRequest == null){
			throw new ProtocolException("Missing query request data");
		}
		if (dataQueryRequest.getResourceCredentials() == null){
			dataQueryRequest.setResourceCredentials(new HashMap<String, String>());
		}
		dataQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());

		dataQueryRequest.setTargetURL(resource.getTargetURL());
		QueryStatus results = resourceWebClient.query(resource.getResourceRSPath(), dataQueryRequest);
		//TODO Deal with possible errors
        //Save query entity
		Query queryEntity = new Query();
		queryEntity.setResourceResultId(results.getResourceResultId());
		queryEntity.setResource(resource);
		queryEntity.setStatus(results.getStatus());
		queryEntity.setStartTime(new Date(results.getStartTime()));
		queryEntity.setQuery(dataQueryRequest.getQuery().toString());
		queryEntity.setMetadata(results.getResultMetadata());
		queryRepo.persist(queryEntity);

		logger.debug("PicsureQueryService() persisted queryEntity with id: " + queryEntity.getUuid());
		results.setPicsureResultId(queryEntity.getUuid());
		//In cases where there is no resource result id, the picsure result id will stand in
		if (queryEntity.getResourceResultId() == null){
		    results.setResourceResultId(queryEntity.getUuid().toString());
			queryEntity.setResourceResultId(results.getPicsureResultId().toString());
			queryRepo.persist(queryEntity);
		}
		results.setResourceID(resourceId);
		return results;
	}

	/**
	 * Retrieves the {@link QueryStatus} for a given queryId by looking up the target resource
	 * from the database and calling the target resource for an updated status. The QueryResults
	 * in the database are updated each time this is called.
	 *
	 * @param queryId - id of targeted resource
	 * @param resourceCredentials - resource specific credentials object
	 * @return {@link QueryStatus}
	 */
	@Transactional
	public QueryStatus queryStatus(UUID queryId, Map<String, String> resourceCredentials) {
		Query query = queryRepo.getById(queryId);
		if (query == null){
			throw new ProtocolException("No query with id " + queryId.toString() + " exists");
		}
		Resource resource = query.getResource();
		if (resource == null){
			throw new ApplicationException("Missing resource");
		}
		if (resource.getTargetURL() == null){
			throw new ApplicationException("Resource is missing target URL");
		}
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setTargetURL(resource.getTargetURL());
		if (resourceCredentials == null){
			throw new NotAuthorizedException("Missing credentials");
		}
		if(resource.getToken()!=null) {
			resourceCredentials.put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		}
		queryRequest.setResourceCredentials(resourceCredentials);
		//Update status on query object
		QueryStatus status = resourceWebClient.queryStatus(resource.getResourceRSPath(), query.getResourceResultId(), queryRequest);
		status.setPicsureResultId(queryId);
		query.setStatus(status.getStatus());
		queryRepo.persist(query);
		status.setStartTime(query.getStartTime().getTime());
		status.setResourceID(resource.getUuid());
		return status;
	}

	/**
	 * Streams the result for a given queryId by looking up the target resource
	 * from the database and calling the target resource for a result. The queryStatus
	 * method should be used to verify that the result is available prior to retrieving it.
	 *
	 * @param queryId - id of target resource
	 * @param resourceCredentials - resource specific credentials object
	 * @return Response
	 */
	@Transactional
	public Response queryResult(UUID queryId, Map<String, String> resourceCredentials) {
		Query query = queryRepo.getById(queryId);
		if (query == null){
			throw new ProtocolException("No query with id " + queryId.toString() + " exists");
		}
		Resource resource = query.getResource();
		if (resource == null){
			throw new ApplicationException("Missing resource");
		}
		if (resource.getTargetURL() == null){
			throw new ApplicationException("Resource is missing target URL");
		}
		QueryRequest queryRequest = new QueryRequest();

		queryRequest.setTargetURL(resource.getTargetURL());

		//TODO Do we need to update any information in the query object?
		if (resourceCredentials == null){
			throw new NotAuthorizedException("Missing credentials");
		}
		resourceCredentials.put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		queryRequest.setResourceCredentials(resourceCredentials);
		return resourceWebClient.queryResult(resource.getResourceRSPath(), query.getResourceResultId(), queryRequest);
	}

    /**
     *
     * @param queryId The UUID of the query to get metadata about
     * @return a QueryStatus object containing the metadata stored about the given query
     */
	public QueryStatus queryMetadata(UUID queryId){
        Query query = queryRepo.getById(queryId);
        if (query == null){
            throw new ProtocolException("No query with id " + queryId.toString() + " exists");
        }
        QueryStatus response = new QueryStatus();
        response.setStartTime(query.getStartTime().getTime());
        response.setPicsureResultId(query.getUuid());
        response.setResourceID(query.getResource().getUuid());
        response.setStatus(query.getStatus());
        response.setResourceResultId(query.getResourceResultId());
        response.setResultMetadata(query.getMetadata());
        return response;
    }
}
