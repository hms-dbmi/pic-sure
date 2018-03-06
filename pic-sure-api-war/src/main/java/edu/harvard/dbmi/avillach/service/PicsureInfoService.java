package edu.harvard.dbmi.avillach.service;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;

public class PicsureInfoService {

	@Inject
	ResourceRepository resourceRepo;
	
	@Inject
	IRCTResourceRS irctResourceRS;
	
	public ResourceInfo info() {
		// TODO Auto-generated method stub
		return null;
	}

	public ResourceInfo info(UUID resourceId) {
		Resource resource = resourceRepo.getById(resourceId);
		
		return irctResourceRS.info();
	}

	public List<Resource> resources() {
		return resourceRepo.list();
	}

}
