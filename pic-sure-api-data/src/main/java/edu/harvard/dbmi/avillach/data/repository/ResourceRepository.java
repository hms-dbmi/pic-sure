package edu.harvard.dbmi.avillach.data.repository;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

import edu.harvard.dbmi.avillach.data.entity.Resource;

import java.util.UUID;

@Transactional
@ApplicationScoped
public class ResourceRepository extends BaseRepository<Resource, UUID> {

	protected ResourceRepository() {
		super(Resource.class);
	}


}
