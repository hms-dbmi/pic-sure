package edu.harvard.dbmi.avillach.service;

import java.io.InputStream;
import java.sql.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.Response;

/**
 * Service handling business logic for queries to resources
 */
public class PicsureQueryService {

	@Inject
	ResourceRepository resourceRepo;

	@Inject
	QueryRepository queryRepo;

	@Inject
	ResourceWebClient resourceWebClient;

	@PersistenceContext
	private EntityManager em;

	/**
	 * Executes a query on a PIC-SURE resource and creates a QueryResults object in the
	 * database for the query.
	 * 
	 * @param resourceId - id of targeted resource
	 * @param dataQueryRequest - - {@link QueryRequest} containing resource specific credentials object
	 *                       and resource specific query (could be a string or a json o
	 * @return {@link QueryResults} object
	 */
	@Transactional
	public QueryStatus query(UUID resourceId, QueryRequest dataQueryRequest) {
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			//TODO Create custom exception
			throw new RuntimeException("No resource with id " + resourceId.toString() + " exists");
		}
		if (dataQueryRequest == null){
			throw new ProtocolException("Missing query request data");
		}
		if (dataQueryRequest.getResourceCredentials() == null){
			dataQueryRequest.setResourceCredentials(new HashMap<String, String>());
		}
		dataQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());

		QueryStatus results = resourceWebClient.query(resource.getBaseUrl(), dataQueryRequest);
		//TODO Deal with possible errors
        //Save query entity
		Query queryEntity = new Query();
		queryEntity.setResourceResultId(results.getResourceResultId());
		queryEntity.setResource(resource);
		queryEntity.setStatus(results.getStatus());
		queryEntity.setStartTime(new Date(results.getStartTime()));
		queryEntity.setQuery(dataQueryRequest.getQuery().toString());
		em.persist(queryEntity);
		results.setPicsureResultId(queryEntity.getUuid());
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
		if (resourceCredentials == null){
			throw new NotAuthorizedException("Missing credentials");
		}
		resourceCredentials.put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		//Update status on query object
		QueryStatus status = resourceWebClient.queryStatus(resource.getBaseUrl(), query.getResourceResultId(), resourceCredentials);
		query.setStatus(status.getStatus());
		em.persist(query);
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
	 * @return {@link QueryResults}
	 */
	@Transactional
	public Response queryResult(UUID queryId, Map<String, String> resourceCredentials) {
		Query query = queryRepo.getById(queryId);
		if (query == null){
			throw new ProtocolException("No query with id " + queryId.toString() + " exists");
		}
		Resource resource = query.getResource();
		//TODO Do we need to update any information in the query object?
		if (resourceCredentials == null){
			throw new NotAuthorizedException("Missing credentials");
		}
		resourceCredentials.put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		return resourceWebClient.queryResult(resource.getBaseUrl(), query.getResourceResultId(), resourceCredentials);
	}

}
