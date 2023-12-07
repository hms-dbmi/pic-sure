package edu.harvard.dbmi.avillach.data.repository;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

import edu.harvard.dbmi.avillach.data.entity.Resource;

import java.util.Optional;
import java.util.UUID;

@Transactional
@ApplicationScoped
public class ResourceRepository extends BaseRepository<Resource, UUID> {

	protected ResourceRepository() {
		super(Resource.class);
	}

    private Optional<String> targetStack = Optional.ofNullable(System.getProperty("TARGET_STACK", null));

    public Resource getById(UUID id) {
        Resource resource = super.getById(id);
        targetStack.ifPresent(stack -> {
            resource.setResourceRSPath(resource.getResourceRSPath().replace("___target_stack___", stack));
        });
        return resource;
    }
}
