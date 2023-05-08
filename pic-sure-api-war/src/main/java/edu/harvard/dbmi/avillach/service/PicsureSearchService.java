package edu.harvard.dbmi.avillach.service;

import java.util.HashMap;
import java.util.UUID;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
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
		if (resourceId == null){
			throw new ProtocolException(ProtocolException.MISSING_RESOURCE_ID);
		}
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null) {
			throw new ProtocolException(ProtocolException.RESOURCE_NOT_FOUND + resourceId.toString());
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}
		if (searchQueryRequest == null){
			throw new ProtocolException(ProtocolException.MISSING_DATA);
		}

		if (searchQueryRequest.getResourceCredentials() == null){
			searchQueryRequest.setResourceCredentials(new HashMap<String, String>());
		}
		return resourceWebClient.search(resource.getResourceRSPath(), searchQueryRequest);
	}

	public PaginatedSearchResult<?> searchConceptValues(UUID resourceId, QueryRequest queryRequest, String conceptPath, String query, Integer page, Integer size) {
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ProtocolException(ProtocolException.RESOURCE_NOT_FOUND + resourceId.toString());
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}
		return resourceWebClient.searchConceptValues(resource.getResourceRSPath(), queryRequest, conceptPath, query, page, size);
	}

}
