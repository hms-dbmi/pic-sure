package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserMetadataMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;

/**
 * Service handling business logic for UserMetadataMappings.
 */
public class UserMetadataMappingService extends BaseEntityService<UserMetadataMapping>{

    Logger logger = LoggerFactory.getLogger(UserMetadataMappingService.class);

    @Inject
    UserMetadataMappingRepository userMetadataMappingRepo;

    public UserMetadataMappingService() {
        super(UserMetadataMapping.class);
    }

    public List<UserMetadataMapping> getAllMappingsForConnection(String connectionId) {
    		return userMetadataMappingRepo.findByConnection(connectionId);
    }
    
}
