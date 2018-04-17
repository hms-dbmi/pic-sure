package edu.harvard.dbmi.avillach.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PicsureInfoService {

	Logger logger = LoggerFactory.getLogger(PicsureInfoService.class);

	@Inject
	ResourceRepository resourceRepo;

	
	/**
	 * Retrieve resource info for a specific resource.
	 * 
	 * @param resourceId - Resource UUID
	 * @param resourceCredentials - Resource specific credentials map
	 * @return a {@link edu.harvard.dbmi.avillach.domain.ResourceInfo ResourceInfo}
	 */
	public ResourceInfo info(UUID resourceId, String resourceCredentials) {

		// please use {}-placeholders to put any of your parameters into the log String
		logger.debug("Entering info with resourceId: {}", resourceId);
		Resource resource = resourceRepo.getById(resourceId);
		//return resourceWebClient.info(resource.getBaseUrl(), resourceCredentials);
		return null;
	}

	/**
	 * Retrieve a list of all available resources.
	 * 
	 * @return List containing limited metadata about all available resources and ids.
	 */
	public List<Resource> resources() {
		return resourceRepo.list();
	}

}
