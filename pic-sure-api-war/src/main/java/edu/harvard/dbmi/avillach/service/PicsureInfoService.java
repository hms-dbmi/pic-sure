package edu.harvard.dbmi.avillach.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;

public class PicsureInfoService {

	@Inject
	ResourceRepository resourceRepo;
	
	public ResourceInfo info() {
		// TODO Auto-generated method stub
		return null;
	}

	public ResourceInfo info(UUID resourceId, Map<String, String> resourceCredentials) {
		Resource resource = resourceRepo.getById(resourceId);	
		// TODO call the resource through some kind of web client to get /resources
		return null;
	}

	public List<Resource> resources() {
		return resourceRepo.list();
	}

}
