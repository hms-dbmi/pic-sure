package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PicsureInfoService {

	Logger logger = LoggerFactory.getLogger(PicsureInfoService.class);

	@Inject
	ResourceRepository resourceRepo;

	@Inject
	ResourceWebClient resourceWebClient;

	/**
	 * Retrieve resource info for a specific resource.
	 *
	 * @param resourceId - Resource UUID
	 * @param resourceCredentials - Resource specific credentials map
	 * @return a {@link edu.harvard.dbmi.avillach.domain.ResourceInfo ResourceInfo}
	 */
	public ResourceInfo info(UUID resourceId, Map<String, String> resourceCredentials) {
		Resource resource = resourceRepo.getById(resourceId);
		if (resource == null){
			throw new ProtocolException("No resource with id " + resourceId.toString() + " exists");
		}
		if (resourceCredentials == null){
			resourceCredentials = new HashMap<String, String>();
		}
		resourceCredentials.put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		return resourceWebClient.info(resource.getBaseUrl(), resourceCredentials);
	}

	/**
	 * Retrieve a list of all available resources.
	 *
	 * @return List containing limited metadata about all available resources and ids.
	 */
	public List<Resource> resources() {
		//TODO Need to limit the metadata returned
		return resourceRepo.list();
	}

}
