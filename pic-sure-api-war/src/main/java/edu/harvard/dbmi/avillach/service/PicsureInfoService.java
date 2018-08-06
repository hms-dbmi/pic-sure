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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PicsureInfoService extends PicsureBaseService{

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
		if (resource.getResourceRSPath() == null){
			throw new ApplicationException("Resource is missing RS path");
		}
		if (resource.getTargetURL() == null){
			throw new ApplicationException("Resource is missing target URL");
		}
		if (resourceCredentials == null){
			resourceCredentials = new HashMap<String, String>();
		}
		resourceCredentials.put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setResourceCredentials(resourceCredentials);
		queryRequest.setTargetURL(resource.getTargetURL());
		return resourceWebClient.info(TARGET_PICSURE_URL + "/" + resource.getResourceRSPath(), queryRequest);
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
