package edu.harvard.hms.dbmi.avillach.auth.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.auth0.client.auth.AuthAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.UserInfo;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;

public class Auth0UserMatchingService {

	@Autowired
	UserRepository userRepo;
	
	/**
	 * Retrieve a user profile by access_token and match it to a pre-created user in the database using
	 * pre-configured matching rules. 
	 * 
	 * @param access_token An auth0 access_token acquired through the login flow.
	 * @return The user that was matched or null if no match was possible.
	 * @throws Auth0Exception
	 */
	public User matchTokenToUser(String access_token) throws Auth0Exception {
		// This retrieves a map of UserInfo as JSON.
		UserInfo info = new AuthAPI("avillachlab.auth0.com", "", "").userInfo(access_token).execute();
		
		// Available as a map, but can always be ObjectMapper'd back to JSON for matching using libraries that do such things
		Map<String, Object> userInfo = info.getValues();
		
		// We will want to be able to go through all the non-previously matched users in the database that
		// were created for the connection in the userInfo identities section. This probably means addding
		// a connection id to the user entity as well as a flag for if that user has been matched already.
		
		// Here we return the matched user, or null if we can't match. An unmatched user should be logged at
		// the very least. We will eventually want to provide an interface for the admin to try and match the
		// user manually potentially resulting in a new matching 
		return null;
	}
	
}
