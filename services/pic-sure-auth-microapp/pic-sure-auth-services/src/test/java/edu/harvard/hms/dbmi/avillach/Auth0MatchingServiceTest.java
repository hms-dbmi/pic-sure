package edu.harvard.hms.dbmi.avillach;

import com.auth0.exception.Auth0Exception;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.rest.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.Auth0UserMatchingService;
import edu.harvard.hms.dbmi.avillach.auth.service.UserMetadataMappingService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class Auth0MatchingServiceTest {

    @Mock
    UserRepository userRepo = mock(UserRepository.class);

    @Mock
    UserMetadataMappingService mappingService = mock(UserMetadataMappingService.class);

    @Mock
    UserService userService = mock(UserService.class);

    @InjectMocks
    Auth0UserMatchingService cut = new Auth0UserMatchingService();

    User persistedUser;
    ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() throws Auth0Exception {
        MockitoAnnotations.initMocks(this);
        //Instead of calling the database
        doAnswer(invocation -> (listUnmatchedByConnectionIdMock(invocation.getArgument(0)))).
                when(userRepo).listUnmatchedByConnectionId(any());
        doAnswer(invocation -> (getAllMappingsForConnectionMock(invocation.getArgument(0)))).
                when(mappingService).getAllMappingsForConnection(any());
        //So we can check that the user is persisted
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                List<User> userList = invocation.getArgument(0);
                persistedUser = userList.get(0);
                return null;
            }
        }).when(userService).updateUser(any(List.class));
    }

    @Test
    public void testMatchTokenToUser() {
        String ldapToken = "ldap-connector-access-token";
        String githubToken = "github-access-token";
        String nihToken = "nih-gov-prod-access-token";

        try {
            JsonNode userInfo = mockAuthAPIUserInfo(ldapToken);

            //Test when everything works fine
            User result = cut.matchTokenToUser(userInfo);
            assertNotNull(result);
            assertNotNull(result.getAuth0metadata());
            assertNotNull(result.getSubject());
            assertEquals("ad|ldap-connector|blablablablablablablablablablablabla", result.getSubject());
            assertTrue(result.isMatched());
            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertEquals(persistedUser.getAuth0metadata(), result.getAuth0metadata());
            assertNotNull(persistedUser.getSubject());
            assertEquals("ad|ldap-connector|blablablablablablablablablablablabla", persistedUser.getSubject());
            assertTrue(persistedUser.isMatched());
            //Reset
            persistedUser = null;

            //Test when multiple mappings in database
            userInfo = mockAuthAPIUserInfo(githubToken);
            result = cut.matchTokenToUser(userInfo);
            assertNotNull(result);
            assertNotNull(result.getAuth0metadata());
            assertNotNull(result.getSubject());
            assertEquals("github|0000000", result.getSubject());
            assertTrue(result.isMatched());
            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertEquals(persistedUser.getAuth0metadata(), result.getAuth0metadata());
            assertNotNull(persistedUser.getSubject());
            assertEquals("github|0000000", persistedUser.getSubject());
            assertTrue(persistedUser.isMatched());

            persistedUser = null;

            //Test when path not found in user generalmetadata
            userInfo = mockAuthAPIUserInfo(nihToken);
            result = cut.matchTokenToUser(userInfo);
            assertNotNull(result);
            assertNotNull(result.getAuth0metadata());
            assertNotNull(result.getSubject());
            assertEquals("samlp|NOBODY", result.getSubject());
            assertTrue(result.isMatched());
            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertEquals(persistedUser.getAuth0metadata(), result.getAuth0metadata());
            assertNotNull(persistedUser.getSubject());
            assertEquals("samlp|NOBODY", persistedUser.getSubject());
            assertTrue(persistedUser.isMatched());

            persistedUser = null;

            //Test when no user matches
            userInfo = mockAuthAPIUserInfo("no-user-token");
            result = cut.matchTokenToUser(userInfo);
            assertNull(result);

            //Test when path not found in auth0metadata -- This is a problem with the mapping data in the database
            userInfo = mockAuthAPIUserInfo("invalid-path-token");
            result = cut.matchTokenToUser(userInfo);
            assertNull(result);

            //Test when no mappings in database -- We have no mappings set up for this yet
            userInfo = mockAuthAPIUserInfo("no-mapping-connection-token");
            result = cut.matchTokenToUser(userInfo);
            assertNull(result);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<User> listUnmatchedByConnectionIdMock(String connectionId) {
        List<User> allMappings = List.of(
                new User().setConnectionId("ldap-connector").setGeneralMetadata("{ \"email\": \"foo@childrens.harvard.edu\", \"fullName\" : \"Bruce Banner\"}"),
                new User().setConnectionId("ldap-connector").setGeneralMetadata("{ \"email\": \"foobar@childrens.harvard.edu\", \"fullName\" : \"Scott Lang\"}"),
                new User().setConnectionId("nih-gov-prod").setGeneralMetadata("{ \"email\": \"foo@nih.gov\", \"fullName\" : \"Piotr Rasputin\"}"),
                new User().setConnectionId("nih-gov-prod").setGeneralMetadata("{ \"email\": \"foobar@nih.gov\", \"fullName\" : \"Matthew Murdock\", \"nih-userid\" : \"NOBODY\"}"),
                new User().setConnectionId("github").setGeneralMetadata("{ \"email\": \"blablabla@gmail.com\", \"fullName\" : \"Some Girl\"}"),
                new User().setConnectionId("github").setGeneralMetadata("{ \"email\": \"blablabla@gmail.com\", \"fullName\" : \"Jean Grey\"}"),
                new User().setConnectionId("no-mapping-connection").setGeneralMetadata("{ \"email\": \"foo@bar.com\", \"fullName\" : \"Luke Cage\"}")
        );
        return allMappings.stream().filter((User user) -> {
            return user.getConnectionId().equalsIgnoreCase(connectionId);
        }).collect(Collectors.toList());
    }

    private List<UserMetadataMapping> getAllMappingsForConnectionMock(String connectionId) {
        List<UserMetadataMapping> allMappings = List.of(
                new UserMetadataMapping().setConnection(new Connection().setId("ldap-connector")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"),
                new UserMetadataMapping().setConnection(new Connection().setId("nih-gov-prod")).setGeneralMetadataJsonPath("$.nih-userid").setAuth0MetadataJsonPath("$.identities[0].user_id"),
                new UserMetadataMapping().setConnection(new Connection().setId("github")).setGeneralMetadataJsonPath("$.full_name").setAuth0MetadataJsonPath("$.name"),
                new UserMetadataMapping().setConnection(new Connection().setId("github")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.emails[?(@.primary == true)].email"),
                new UserMetadataMapping().setConnection(new Connection().setId("no-user-connection")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"),
                new UserMetadataMapping().setConnection(new Connection().setId("invalid-path")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.noPath")

                );
        return allMappings.stream().filter((UserMetadataMapping mapping) -> {
            return mapping.getConnection().getId().equalsIgnoreCase(connectionId);
        }).collect(Collectors.toList());
    }

    public JsonNode mockAuthAPIUserInfo(String accessToken) throws IOException {
        Map<String, String> map = Map.of("ldap-connector-access-token",
                "{    \"name\": \"Guy,Some\",    \"family_name\": \"Guy\",    \"given_name\": \"Some\",    \"nickname\": \"CH000000000\",    \"groups\": [],    \"emails\": [        \"foo@childrens.harvard.edu\"    ],    \"dn\": \"CN=CH0000000,OU=users,DC=chbdir,DC=org\",    \"distinguishedName\": \"CN=CH0000000,OU=users,DC=chbdir,DC=org\",    \"organizationUnits\": \"CN=CH0000000,OU=users,DC=chbdir,DC=org\",    \"email\": \"foo@childrens.harvard.edu\",    \"updated_at\": \"2018-10-04T18:28:23.371Z\",    \"picture\": \"https://s.gravatar.com/avatar/blablablablablablablablablablablabla?s=480&r=pg&d=https%3A%2F%2Fcdn.auth0.com%2Favatars%2Fsp.png\",    \"user_id\": \"ad|ldap-connector|blablablablablablablablablablablabla\",    \"identities\": [        {            \"user_id\": \"ldap-connector|blablablablablablablablablablablabla\",            \"provider\": \"ad\",            \"connection\": \"ldap-connector\",            \"isSocial\": false        }    ],    \"created_at\": \"2018-01-26T14:06:50.413Z\",    \"username\": \"CH0000000\",    \"app_metadata\": {        \"roles\": [            \"ROLE_CITI_USER\"        ]    },    \"last_ip\": \"134.174.140.32\",    \"last_login\": \"2018-10-04T18:28:23.091Z\",    \"logins_count\": 399,    \"blocked_for\": [],    \"guardian_authenticators\": []}",
                "github-access-token","{    \"email\": \"blablabla@gmail.com\",    \"name\": \"Some Girl\",    \"picture\": \"https://avatars3.githubusercontent.com/u/0000000000?v=4\",    \"nickname\": \"blablabla\",    \"gravatar_id\": \"\",    \"url\": \"https://api.github.com/users/blablabla\",    \"html_url\": \"https://github.com/blablabla\",    \"followers_url\": \"https://api.github.com/users/blablabla/followers\",    \"following_url\": \"https://api.github.com/users/blablabla/following{/other_user}\",    \"gists_url\": \"https://api.github.com/users/blablabla/gists{/gist_id}\",    \"starred_url\": \"https://api.github.com/users/blablabla/starred{/owner}{/repo}\",    \"subscriptions_url\": \"https://api.github.com/users/blablabla/subscriptions\",    \"organizations_url\": \"https://api.github.com/users/blablabla/orgs\",    \"repos_url\": \"https://api.github.com/users/blablabla/repos\",    \"events_url\": \"https://api.github.com/users/blablabla/events{/privacy}\",    \"received_events_url\": \"https://api.github.com/users/blablabla/received_events\",    \"type\": \"User\",    \"site_admin\": false,    \"location\": \"Nowhere, USA\",    \"hireable\": true,    \"public_repos\": 8,    \"public_gists\": \"0\",    \"followers\": 3,    \"following\": 1,    \"updated_at\": \"2018-09-20T18:47:43.703Z\",    \"emails\": [        {            \"email\": \"blablabla@gmail.com\",            \"primary\": true,            \"verified\": true,            \"visibility\": \"public\"        },        {            \"email\": \"blablabla@users.noreply.github.com\",            \"primary\": false,            \"verified\": true,            \"visibility\": null        }    ],    \"email_verified\": true,    \"user_id\": \"github|0000000\",    \"identities\": [        {            \"provider\": \"github\",            \"user_id\": \"000000000\",            \"connection\": \"github\",            \"isSocial\": true        }    ],    \"created_at\": \"2016-10-22T22:38:20.437Z\",    \"blog\": \"\",    \"node_id\": \"blablabla=\",    \"app_metadata\": {        \"roles\": [            \"ROLE_CITI_USER\"        ]    },    \"last_ip\": \"134.174.140.198\",    \"last_login\": \"2018-09-20T18:47:43.491Z\",    \"logins_count\": 71,    \"blocked_for\": [],    \"guardian_authenticators\": []}",
                "nih-gov-prod-access-token","{    \"email\": \"NOBODY\",    \"sessionIndex\": \"blablablabla\",    \"UserPrincipalName\": \"\",    \"Mail\": \"\",    \"FirstName\": \"\",    \"LastName\": \"\",    \"MiddleName\": \"\",    \"NEDID\": \"\",    \"nameIdAttributes\": {        \"value\": \"NOBODY\",        \"Format\": \"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\"    },    \"authenticationmethod\": \"urn:oasis:names:tc:SAML:2.0:ac:classes:Password\",    \"issuer\": \"https://auth.nih.gov/IDP\",    \"updated_at\": \"2018-07-23T19:32:51.505Z\",    \"name\": \"\",    \"picture\": \"https://cdn.auth0.com/avatars/default.png\",    \"user_id\": \"samlp|NOBODY\",    \"nickname\": \"\",    \"identities\": [        {            \"user_id\": \"NOBODY\",            \"provider\": \"samlp\",            \"connection\": \"nih-gov-prod\",            \"isSocial\": false        }    ],    \"created_at\": \"2018-04-02T13:10:25.654Z\",    \"app_metadata\": {        \"roles\": [            \"ROLE_CITI_USER\"        ]    },    \"last_ip\": \"134.174.140.195\",    \"last_login\": \"2018-07-23T19:32:51.254Z\",    \"logins_count\": 12,    \"blocked_for\": [],    \"guardian_authenticators\": []}",
                "no-mapping-connection-token","{    \"email\": \"foo@bar.com\",     \"UserName\": \"foooo\",     \"FirstName\": \"foo\",    \"LastName\": \"oooo\",\"user_id\": \"samlp|fooBar\",    \"identities\": [        {            \"user_id\": \"fooBar\",            \"provider\": \"samlp\",            \"connection\": \"no-mapping-connection\",            \"isSocial\": false        }    ]}",
                "invalid-path-token","{    \"email\": \"bar@foo.com\",     \"UserName\": \"bahh\",     \"user_id\": \"samlp|barFoo\",    \"identities\": [        {            \"user_id\": \"barFoo\",            \"provider\": \"samlp\",            \"connection\": \"invalid-path\",            \"isSocial\": true        }    ]}",
                "no-user-token","{    \"email\": \"no@user.com\",     \"UserName\": \"nooooooo\",     \"user_id\": \"samlp|noUser\",    \"identities\": [        {            \"user_id\": \"noUser\",            \"provider\": \"samlp\",            \"connection\": \"no-user-connection\",            \"isSocial\": false        }    ]}"
        );
        String result =  map.get(accessToken);
        Map<String, Object> jsonMap = mapper.readValue(result,
                new TypeReference<Map<String,Object>>(){});
        return mapper.valueToTree(jsonMap);
    }

}
