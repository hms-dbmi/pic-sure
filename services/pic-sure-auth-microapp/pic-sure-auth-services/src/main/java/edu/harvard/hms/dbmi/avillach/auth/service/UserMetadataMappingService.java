package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserMetadataMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * Service handling business logic for UserMetadataMappings.
 */
public class UserMetadataMappingService extends BaseEntityService<UserMetadataMapping>{

    Logger logger = LoggerFactory.getLogger(UserMetadataMappingService.class);

    @Inject
    UserMetadataMappingRepository userMetadataMappingRepo;

    @Inject
    ConnectionRepository connectionRepo;

    public UserMetadataMappingService() {
        super(UserMetadataMapping.class);
    }

    public List<UserMetadataMapping> getAllMappingsForConnection(String connectionId) {
    		return userMetadataMappingRepo.findByConnection(connectionId);
    }
   
    public Response addMappings(List<UserMetadataMapping> mappings){
        String errorMessage = "The following connectionIds do not exist:\n";
        boolean error = false;
        for (UserMetadataMapping umm : mappings){
            Connection c = connectionRepo.findConnectionById(umm.getConnection().getId());
            if (c == null){
                error = true;
                errorMessage += umm.getConnection().getId() + "\n";
            } else {
                umm.setConnection(c);
            }
        }
        if (error){
            return Response.ok(errorMessage).build();
        }
		return addEntity(mappings, userMetadataMappingRepo);
    }

	public List<UserMetadataMapping> getAllMappings() {
		return userMetadataMappingRepo.list();
	}
    
}
