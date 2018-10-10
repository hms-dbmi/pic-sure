package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserMetadataMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

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
    
    public List<UserMetadataMapping> getAllMappingsForConnectionMock(String connectionId){
    		List<UserMetadataMapping> allMappings = List.of(
    				new UserMetadataMapping().setConnectionId("ldap-connector").setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"),
    				new UserMetadataMapping().setConnectionId("nih-gov-prod").setGeneralMetadataJsonPath("$.nih-userid").setAuth0MetadataJsonPath("$.identities.userid"),
    				new UserMetadataMapping().setConnectionId("github").setGeneralMetadataJsonPath("$.full_name").setAuth0MetadataJsonPath("$.name"),
    				new UserMetadataMapping().setConnectionId("github").setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.emails[?(@.primary == true].email")
    				);
    		return allMappings.stream().filter((UserMetadataMapping mapping)->{
    			return mapping.getConnectionId().equalsIgnoreCase(connectionId);
    		}).collect(Collectors.toList());
    }

	public List<UserMetadataMapping> getAllMappings() {
		return userMetadataMappingRepo.list();
	}
    
}
