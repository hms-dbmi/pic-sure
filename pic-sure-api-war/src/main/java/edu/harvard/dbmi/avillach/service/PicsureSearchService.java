package edu.harvard.dbmi.avillach.service;

import java.util.UUID;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

import javax.inject.Inject;

public class PicsureSearchService {

	@Inject
	ResourceRepository resourceRepo;

	@Inject
	ResourceWebClient resourceWebClient;

	/**
	 * Executes a concept search against a target resource
	 * 
	 * @param resourceId - UUID of target resource
	 * @param searchQueryRequest - {@link QueryRequest} containing resource specific credentials object
     *                       and resource specific query (could be a string or a json object)
	 * @return {@link SearchResults}
	 */
	public SearchResults search(UUID resourceId, QueryRequest searchQueryRequest) {
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ProtocolException("No resource with id " + resourceId.toString() + " exists");
		}
		return resourceWebClient.search(resource.getBaseUrl(), searchQueryRequest);
	}

}
