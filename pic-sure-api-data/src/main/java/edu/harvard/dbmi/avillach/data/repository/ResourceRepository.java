package edu.harvard.dbmi.avillach.data.repository;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

import edu.harvard.dbmi.avillach.data.entity.Resource;

@Transactional
@ApplicationScoped
public class ResourceRepository extends BaseRepository<Resource> {

	public ResourceRepository() {
		super(new Resource());
	}

	
}
