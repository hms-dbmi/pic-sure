package edu.harvard.dbmi.avillach.service;

import java.util.Map;
import java.util.UUID;

import edu.harvard.dbmi.avillach.domain.SearchResults;

public class PicsureSearchService {

	/**
	 * Executes a concept search against a target resource
	 * 
	 * @param resourceId - UUID of target resource
	 * @param resourceCredentials - resource specific credentials object
	 * @param query - resource specific query (could be a string or a json object)
	 * @return {@link SearchResults}
	 */
	public SearchResults search(UUID resourceId, Map<String, String> resourceCredentials, String query) {
		// TODO Auto-generated method stub
		return null;
	}

}
