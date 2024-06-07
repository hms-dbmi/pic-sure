package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.*;

import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <p>Matches users created by admins with user profiles created by a 3rd party Oauth provider.</p>
 */
@Service
public class OauthUserMatchingService {

	private final Logger logger = LoggerFactory.getLogger(OauthUserMatchingService.class);

	private final UserRepository userRepo;

	private final UserService userService;

	private final UserMetadataMappingService mappingService;

	private final ConnectionRepository connectionRepo;

	private final ObjectMapper mapper = new ObjectMapper();

	@Autowired
    public OauthUserMatchingService(UserRepository userRepo, UserService userService, UserMetadataMappingService mappingService, ConnectionRepository connectionRepo) {
        this.userRepo = userRepo;
        this.userService = userService;
        this.mappingService = mappingService;
        this.connectionRepo = connectionRepo;
    }

	/**
	 * Retrieve a user profile by access_token and match it to a pre-created user in the database using
	 * pre-configured matching rules. 
	 * 
	 * @param userInfo UserInfo returned from auth0
	 * @return The user that was matched or null if no match was possible.
	 */
	public User matchTokenToUser(JsonNode userInfo) {
		// This retrieves a map of UserInfo as JSON.
		try {
			String userInfoString = mapper.writeValueAsString(userInfo);
            logger.info("Attempting to find match for user with info: {}", userInfo);

			//Parse this once so it doesn't get re-parsed every time we read from it
			Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL).addOptions(Option.ALWAYS_RETURN_LIST);
			Object parsedInfo = conf.jsonProvider().parse(userInfoString);
			//Return lists or null so that we don't have to worry about whether it's a single object or an array, or catch errors
			List<String> connections = JsonPath.using(conf).parse(parsedInfo).read("$.identities[0].connection");
			String connectionId = connections.getFirst();
			Optional<Connection> connection = connectionRepo.findById(connectionId);
			if (connection.isEmpty()) {
				//We don't have a connection for this user
				logger.warn("Unable to find connection with id {}", connectionId);
				return null;
			}

			List<UserMetadataMapping> mappings = mappingService.getAllMappingsForConnection(connection.get());

			if (mappings == null || mappings.isEmpty()) {
				//We don't have any mappings for this connection yet
                logger.warn("Unable to find user metadata mappings for connectionId {}", connection);
				return null;
			}

			//We only care about unmatched users
			List<User> users = userRepo.findByConnectionAndMatched(connection.get(), false);
			if (users == null || users.isEmpty()) {
                logger.info("No unmatched users exist with connectionId {}", connection);
				return null;
			}
			for (UserMetadataMapping umm : mappings) {
				List<String> auth0values = JsonPath.using(conf).parse(parsedInfo).read(umm.getAuth0MetadataJsonPath());
				if (auth0values == null || auth0values.isEmpty()) {
					//Well, nothing found, let's move on.
                    logger.info("Fetched data has no value at {}", umm.getAuth0MetadataJsonPath());
					break;
				}
				String auth0value = auth0values.get(0);
				for (User u : users) {
					List<String> values = null;
					try{
						values = JsonPath.using(conf).parse(u.getGeneralMetadata()).read(umm.getGeneralMetadataJsonPath());
					} catch (JsonPathException e) {
                        logger.warn("User {} has invalid general metadata: {}", u.getUuid(), u.getGeneralMetadata());
						continue;
					}
					if (values == null || values.isEmpty()) {
                        logger.warn("User {} has no value at {}", u.getUuid(), umm.getGeneralMetadataJsonPath());
						continue;
					}
					String generalValue = values.get(0);
					if (auth0value.equalsIgnoreCase(generalValue)) {
						//Match found!!
						String userId = JsonPath.read(parsedInfo, "$.user_id");
                        logger.info("Matching user with user_id {}", userId);
						u.setAuth0metadata(userInfoString);
						u.setMatched(true);
						u.setSubject(userId);
						userService.save(u);
						return u;
					}
				}
			}
		} catch (JsonProcessingException e ){
			logger.error("Unable to read UserInfo");
		}
		//No user found
		logger.info("No matching user found");
		return null;
	}

}
