package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static edu.harvard.dbmi.avillach.util.Utilities.getAuthOrOpenAccessResourceUUIDFromHeaderIfPresent;

public class PicsureInfoService {

	private final Logger logger = LoggerFactory.getLogger(PicsureQueryService.class);

	@Context
	private HttpHeaders headers;

	@Inject
	ResourceRepository resourceRepo;

	@Inject
	ResourceWebClient resourceWebClient;

	/**
	 * Retrieve resource info for a specific resource.
	 *
	 * @param resourceId - Resource UUID
	 * @param credentialsQueryRequest - Contains resource specific credentials map
	 * @return a {@link edu.harvard.dbmi.avillach.domain.ResourceInfo ResourceInfo}
	 */
	public ResourceInfo info(UUID resourceId, QueryRequest credentialsQueryRequest) {
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ProtocolException(ProtocolException.RESOURCE_NOT_FOUND + resourceId.toString());
		}
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
		}
		if (credentialsQueryRequest == null){
			credentialsQueryRequest = new QueryRequest();
		}
		if (credentialsQueryRequest.getResourceCredentials() == null){
			credentialsQueryRequest.setResourceCredentials(new HashMap<String, String>());
		}

		logger.info("path=/info/{resourceId}, resourceId={}, authOrOpenAccessResourceUUID={}, credentialsQueryRequest={}",
				resourceId,
				getAuthOrOpenAccessResourceUUIDFromHeaderIfPresent(headers),
				credentialsQueryRequest
		);

		credentialsQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		return resourceWebClient.info(resource.getResourceRSPath(), credentialsQueryRequest);
	}

	/**
	 * Retrieve a list of all available resources.
	 *
	 * @return List containing limited metadata about all available resources and ids.
	 */
	public Map<UUID, String> resources() {
		logger.info("/info/resources request with headers: " + headers.getRequestHeaders().toString());
		logger.info("path=/info/resources, resourceUUID={}", getAuthOrOpenAccessResourceUUIDFromHeaderIfPresent(headers));
		return resourceRepo.list().stream().collect(Collectors.toMap(Resource::getUuid, Resource::getName));
	}
}
