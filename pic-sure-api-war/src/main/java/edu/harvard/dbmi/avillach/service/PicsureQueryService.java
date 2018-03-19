package edu.harvard.dbmi.avillach.service;

import java.util.Map;
import java.util.UUID;

import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;

/**
 * Service handling business logic for queries to resources
 */
public class PicsureQueryService {

	/**
	 * Executes a query on a PIC-SURE resource and creates a QueryResults object in the
	 * database for the query.
	 * 
	 * @param resourceId - id of targeted resource
	 * @param resourceCredentials - resource specific map of resource credentials 
	 * @param queryJson - resource specific query json (or just a string)
	 * @return {@link QueryResults} object
	 */
	public QueryResults query(UUID resourceId, Map<String, String> resourceCredentials, String queryJson) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Retrieves the {@link QueryStatus} for a given queryId by looking up the target resource 
	 * from the database and calling the target resource for an updated status. The QueryResults
	 * in the database are updated each time this is called.
	 * 
	 * @param queryId
	 * @param resourceCredentials
	 * @return {@link QueryStatus}
	 */
	public QueryStatus queryStatus(UUID queryId, String resourceCredentials) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Streams the result for a given queryId by looking up the target resource
	 * from the database and calling the target resource for a result. The queryStatus
	 * method should be used to verify that the result is available prior to retrieving it.
	 * 
	 * @param queryId
	 * @return {@link QueryResults}
	 */
	public QueryResults queryResult(UUID queryId, String resourceCredentials) {
		// TODO Auto-generated method stub
		return null;
	}

}
