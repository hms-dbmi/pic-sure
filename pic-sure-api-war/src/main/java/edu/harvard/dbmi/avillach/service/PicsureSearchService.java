package edu.harvard.dbmi.avillach.service;

import java.util.HashMap;
import java.util.UUID;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
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

	public final static String MISSING_DATA = "Missing query request data";
	public final static String MISSING_TARGET_URL = "Resource is missing target URL";
	public final static String MISSING_RESOURCE_PATH = "Resource is missing resourceRS path";
	public final static String RESOURCE_NOT_FOUND = "No resource with id: ";
	public final static String MISSING_RESOURCE_ID = "Missing resource id";

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
			throw new ProtocolException(MISSING_RESOURCE_ID);
		}
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ProtocolException(RESOURCE_NOT_FOUND + resourceId.toString());
		}
		if (resource.getTargetURL() == null){
			throw new ApplicationException(MISSING_TARGET_URL);
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(MISSING_RESOURCE_PATH);
		}
		if (searchQueryRequest == null){
			throw new ProtocolException(MISSING_DATA);
		}
		searchQueryRequest.setTargetURL(resource.getTargetURL());

		if (searchQueryRequest.getResourceCredentials() == null){
			searchQueryRequest.setResourceCredentials(new HashMap<String, String>());
		}
		searchQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		return resourceWebClient.search(resource.getResourceRSPath(), searchQueryRequest);
	}

}
