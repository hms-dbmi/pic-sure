package edu.harvard.dbmi.avillach.service;

import java.util.Map;
import java.util.UUID;

import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;

import javax.inject.Inject;

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
	/**
	 * Executes a query on a PIC-SURE resource and creates a QueryResults object in the
	 * database for the query.
	 * 
	 * @param resourceId - id of targeted resource
	 * @param dataQueryRequest - - {@link QueryRequest} containing resource specific credentials object
	 *                       and resource specific query (could be a string or a json o
	 * @return {@link QueryResults} object
	 */
	public QueryResults query(UUID resourceId, QueryRequest dataQueryRequest) {
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			//TODO Create custom exception
			throw new RuntimeException("No resource with id " + resourceId.toString() + " exists");
		}
		return resourceWebClient.query(resource.getBaseUrl(), dataQueryRequest);
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
	public QueryStatus queryStatus(UUID queryId, Map<String, String> resourceCredentials) {
		Query query = queryRepo.getById(queryId);
		if (query == null){
			//TODO Create custom exception
			throw new RuntimeException("No query with id " + queryId.toString() + " exists");
		}
		Resource resource = query.getResource();
		return resourceWebClient.queryStatus(resource.getBaseUrl(), queryId, resourceCredentials);
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
	public QueryResults queryResult(UUID queryId, Map<String, String> resourceCredentials) {
		Query query = queryRepo.getById(queryId);
		if (query == null){
			//TODO Create custom exception
			throw new RuntimeException("No query with id " + queryId.toString() + " exists");
		}
		Resource resource = query.getResource();
		return resourceWebClient.queryResult(resource.getBaseUrl(), queryId, resourceCredentials);

	}

}
