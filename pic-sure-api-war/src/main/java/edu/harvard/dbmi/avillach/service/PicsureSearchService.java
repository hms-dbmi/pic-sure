package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.util.Utilities;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import java.util.HashMap;
import java.util.UUID;

public class PicsureSearchService {

	private final Logger logger = LoggerFactory.getLogger(PicsureSearchService.class);

	private final static ObjectMapper mapper = new ObjectMapper();

	@Inject
	ResourceRepository resourceRepo;

	@Inject
	ResourceWebClient resourceWebClient;

	/**
	 * Executes a concept search against a target resource
	 *
	 * @param resourceId         - UUID of target resource
	 * @param searchQueryRequest - {@link GeneralQueryRequest} containing resource specific credentials object
	 *                           and resource specific query (could be a string or a json object)
	 * @param headers
	 * @return {@link SearchResults}
	 */
	public SearchResults search(UUID resourceId, QueryRequest searchQueryRequest, HttpHeaders headers) {
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

		logger.info("path=/search/{resourceId}, resourceId={}, requestSource={}, searchQueryRequest={}",
				resourceId,
				Utilities.getRequestSourceFromHeader(headers),
				Utilities.convertQueryRequestToString(mapper, searchQueryRequest)
		);

		if (searchQueryRequest.getResourceCredentials() == null){
			searchQueryRequest.setResourceCredentials(new HashMap<String, String>());
		}
		return resourceWebClient.search(resource.getResourceRSPath(), searchQueryRequest);
	}

	public PaginatedSearchResult<?> searchGenomicConceptValues(UUID resourceId, QueryRequest queryRequest, String conceptPath, String query, Integer page, Integer size
			, HttpHeaders headers) {
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ProtocolException(ProtocolException.RESOURCE_NOT_FOUND + resourceId.toString());
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}

		logger.info("path=/search/{resourceId}/concept/{conceptPath}, resourceId={}, requestSource={}, queryRequest={}, conceptPath={}, query={}",
				resourceId,
				Utilities.getRequestSourceFromHeader(headers),
				Utilities.convertQueryRequestToString(mapper, queryRequest),
				conceptPath,
				query);

		return resourceWebClient.searchConceptValues(resource.getResourceRSPath(), queryRequest, conceptPath, query, page, size);
	}

}
