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
	
	private UserMetadataMappingService mappingService = new UserMetadataMappingService();
	
	public String mockAuthAPIUserInfo(String accessToken) {
		Map<String, String> map = Map.of("ldap-connector-access-token", "  {\\r\\n    \\\"name\\\": \\\"Guy,Some\\\",\\r\\n    \\\"family_name\\\": \\\"Guy\\\",\\r\\n    \\\"given_name\\\": \\\"Some\\\",\\r\\n    \\\"nickname\\\": \\\"CH000000000\\\",\\r\\n    \\\"groups\\\": [],\\r\\n    \\\"emails\\\": [\\r\\n        \\\"foo@childrens.harvard.edu\\\"\\r\\n    ],\\r\\n    \\\"dn\\\": \\\"CN=CH0000000,OU=users,DC=chbdir,DC=org\\\",\\r\\n    \\\"distinguishedName\\\": \\\"CN=CH0000000,OU=users,DC=chbdir,DC=org\\\",\\r\\n    \\\"organizationUnits\\\": \\\"CN=CH0000000,OU=users,DC=chbdir,DC=org\\\",\\r\\n    \\\"email\\\": \\\"foo@childrens.harvard.edu\\\",\\r\\n    \\\"updated_at\\\": \\\"2018-10-04T18:28:23.371Z\\\",\\r\\n    \\\"picture\\\": \\\"https://s.gravatar.com/avatar/blablablablablablablablablablablabla?s=480&r=pg&d=https%3A%2F%2Fcdn.auth0.com%2Favatars%2Fsp.png\\\",\\r\\n    \\\"user_id\\\": \\\"ad|ldap-connector|blablablablablablablablablablablabla\\\",\\r\\n    \\\"identities\\\": [\\r\\n        {\\r\\n            \\\"user_id\\\": \\\"ldap-connector|blablablablablablablablablablablabla\\\",\\r\\n            \\\"provider\\\": \\\"ad\\\",\\r\\n            \\\"connection\\\": \\\"ldap-connector\\\",\\r\\n            \\\"isSocial\\\": false\\r\\n        }\\r\\n    ],\\r\\n    \\\"created_at\\\": \\\"2018-01-26T14:06:50.413Z\\\",\\r\\n    \\\"username\\\": \\\"CH0000000\\\",\\r\\n    \\\"app_metadata\\\": {\\r\\n        \\\"roles\\\": [\\r\\n            \\\"ROLE_CITI_USER\\\"\\r\\n        ]\\r\\n    },\\r\\n    \\\"last_ip\\\": \\\"134.174.140.32\\\",\\r\\n    \\\"last_login\\\": \\\"2018-10-04T18:28:23.091Z\\\",\\r\\n    \\\"logins_count\\\": 399,\\r\\n    \\\"blocked_for\\\": [],\\r\\n    \\\"guardian_authenticators\\\": []\\r\\n}",
				"github-access-token", "  {\\r\\n    \\\"email\\\": \\\"blablabla@gmail.com\\\",\\r\\n    \\\"name\\\": \\\"Some Girl\\\",\\r\\n    \\\"picture\\\": \\\"https://avatars3.githubusercontent.com/u/0000000000?v=4\\\",\\r\\n    \\\"nickname\\\": \\\"blablabla\\\",\\r\\n    \\\"gravatar_id\\\": \\\"\\\",\\r\\n    \\\"url\\\": \\\"https://api.github.com/users/blablabla\\\",\\r\\n    \\\"html_url\\\": \\\"https://github.com/blablabla\\\",\\r\\n    \\\"followers_url\\\": \\\"https://api.github.com/users/blablabla/followers\\\",\\r\\n    \\\"following_url\\\": \\\"https://api.github.com/users/blablabla/following{/other_user}\\\",\\r\\n    \\\"gists_url\\\": \\\"https://api.github.com/users/blablabla/gists{/gist_id}\\\",\\r\\n    \\\"starred_url\\\": \\\"https://api.github.com/users/blablabla/starred{/owner}{/repo}\\\",\\r\\n    \\\"subscriptions_url\\\": \\\"https://api.github.com/users/blablabla/subscriptions\\\",\\r\\n    \\\"organizations_url\\\": \\\"https://api.github.com/users/blablabla/orgs\\\",\\r\\n    \\\"repos_url\\\": \\\"https://api.github.com/users/blablabla/repos\\\",\\r\\n    \\\"events_url\\\": \\\"https://api.github.com/users/blablabla/events{/privacy}\\\",\\r\\n    \\\"received_events_url\\\": \\\"https://api.github.com/users/blablabla/received_events\\\",\\r\\n    \\\"type\\\": \\\"User\\\",\\r\\n    \\\"site_admin\\\": false,\\r\\n    \\\"location\\\": \\\"Nowhere, USA\\\",\\r\\n    \\\"hireable\\\": true,\\r\\n    \\\"public_repos\\\": 8,\\r\\n    \\\"public_gists\\\": 0,\\r\\n    \\\"followers\\\": 3,\\r\\n    \\\"following\\\": 1,\\r\\n    \\\"updated_at\\\": \\\"2018-09-20T18:47:43.703Z\\\",\\r\\n    \\\"emails\\\": [\\r\\n        {\\r\\n            \\\"email\\\": \\\"blablabla@gmail.com\\\",\\r\\n            \\\"primary\\\": true,\\r\\n            \\\"verified\\\": true,\\r\\n            \\\"visibility\\\": \\\"public\\\"\\r\\n        },\\r\\n        {\\r\\n            \\\"email\\\": \\\"blablabla@users.noreply.github.com\\\",\\r\\n            \\\"primary\\\": false,\\r\\n            \\\"verified\\\": true,\\r\\n            \\\"visibility\\\": null\\r\\n        }\\r\\n    ],\\r\\n    \\\"email_verified\\\": true,\\r\\n    \\\"user_id\\\": \\\"github|0000000\\\",\\r\\n    \\\"identities\\\": [\\r\\n        {\\r\\n            \\\"provider\\\": \\\"github\\\",\\r\\n            \\\"user_id\\\": 000000000,\\r\\n            \\\"connection\\\": \\\"github\\\",\\r\\n            \\\"isSocial\\\": true\\r\\n        }\\r\\n    ],\\r\\n    \\\"created_at\\\": \\\"2016-10-22T22:38:20.437Z\\\",\\r\\n    \\\"blog\\\": \\\"\\\",\\r\\n    \\\"node_id\\\": \\\"blablabla=\\\",\\r\\n    \\\"app_metadata\\\": {\\r\\n        \\\"roles\\\": [\\r\\n            \\\"ROLE_CITI_USER\\\"\\r\\n        ]\\r\\n    },\\r\\n    \\\"last_ip\\\": \\\"134.174.140.198\\\",\\r\\n    \\\"last_login\\\": \\\"2018-09-20T18:47:43.491Z\\\",\\r\\n    \\\"logins_count\\\": 71,\\r\\n    \\\"blocked_for\\\": [],\\r\\n    \\\"guardian_authenticators\\\": []\\r\\n}\\r\\n",
				"nih-gov-prod-access-token","  {\\r\\n    \\\"email\\\": \\\"NOBODY\\\",\\r\\n    \\\"sessionIndex\\\": \\\"blablablabla\\\",\\r\\n    \\\"UserPrincipalName\\\": \\\"\\\",\\r\\n    \\\"Mail\\\": \\\"\\\",\\r\\n    \\\"FirstName\\\": \\\"\\\",\\r\\n    \\\"LastName\\\": \\\"\\\",\\r\\n    \\\"MiddleName\\\": \\\"\\\",\\r\\n    \\\"NEDID\\\": \\\"\\\",\\r\\n    \\\"nameIdAttributes\\\": {\\r\\n        \\\"value\\\": \\\"NOBODY\\\",\\r\\n        \\\"Format\\\": \\\"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\\\"\\r\\n    },\\r\\n    \\\"authenticationmethod\\\": \\\"urn:oasis:names:tc:SAML:2.0:ac:classes:Password\\\",\\r\\n    \\\"issuer\\\": \\\"https://auth.nih.gov/IDP\\\",\\r\\n    \\\"updated_at\\\": \\\"2018-07-23T19:32:51.505Z\\\",\\r\\n    \\\"name\\\": \\\"\\\",\\r\\n    \\\"picture\\\": \\\"https://cdn.auth0.com/avatars/default.png\\\",\\r\\n    \\\"user_id\\\": \\\"samlp|NOBODY\\\",\\r\\n    \\\"nickname\\\": \\\"\\\",\\r\\n    \\\"identities\\\": [\\r\\n        {\\r\\n            \\\"user_id\\\": \\\"NOBODY\\\",\\r\\n            \\\"provider\\\": \\\"samlp\\\",\\r\\n            \\\"connection\\\": \\\"nih-gov-prod\\\",\\r\\n            \\\"isSocial\\\": false\\r\\n        }\\r\\n    ],\\r\\n    \\\"created_at\\\": \\\"2018-04-02T13:10:25.654Z\\\",\\r\\n    \\\"app_metadata\\\": {\\r\\n        \\\"roles\\\": [\\r\\n            \\\"ROLE_CITI_USER\\\"\\r\\n        ]\\r\\n    },\\r\\n    \\\"last_ip\\\": \\\"134.174.140.195\\\",\\r\\n    \\\"last_login\\\": \\\"2018-07-23T19:32:51.254Z\\\",\\r\\n    \\\"logins_count\\\": 12,\\r\\n    \\\"blocked_for\\\": [],\\r\\n    \\\"guardian_authenticators\\\": []\\r\\n}\\r\\n");
		return map.get(accessToken);
	}
	
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
