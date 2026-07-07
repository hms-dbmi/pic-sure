package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ContextConfiguration(classes = {OauthUserMatchingService.class})
public class Auth0MatchingServiceTest {

    private static final Logger log = LoggerFactory.getLogger(Auth0MatchingServiceTest.class);

    @MockBean
    private UserRepository userRepo;

    @MockBean
    private UserMetadataMappingService mappingService;

    @MockBean
    private UserService userService;

    @MockBean
    private ConnectionRepository connectionRepo;

    @Autowired
    private OauthUserMatchingService cut;

    private User persistedUser;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        //Instead of calling the database
        doAnswer(invocation -> (listUnmatchedByConnectionIdMock(invocation.getArgument(0)))).
                when(userRepo).findByConnectionAndMatched(any(Connection.class), anyBoolean());
        doAnswer(invocation -> (getAllMappingsForConnectionMock(invocation.getArgument(0)))).
                when(mappingService).getAllMappingsForConnection(any(Connection.class));

        doAnswer(invocation -> {
            String connectionId = invocation.getArgument(0);
            log.info("Mocking connection with id: {}", connectionId);
            return mockConnection(connectionId);
        }).when(connectionRepo).findById(anyString());

        //So we can check that the user is persisted
        doAnswer(invocation -> {
            persistedUser = invocation.getArgument(0);
            return null;
        }).when(userService).save(any(User.class));
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
            log.info("Result: " + result);
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

    private List<User> listUnmatchedByConnectionIdMock(Connection connectionId) {
        List<User> allMappings = List.of(
                new User().setConnection(new Connection().setId("ldap-connector")).setGeneralMetadata("{ \"email\": \"foo@childrens.harvard.edu\", \"fullName\" : \"Bruce Banner\"}"),
                new User().setConnection(new Connection().setId("ldap-connector")).setGeneralMetadata("{ \"email\": \"foobar@childrens.harvard.edu\", \"fullName\" : \"Scott Lang\"}"),
                new User().setConnection(new Connection().setId("nih-gov-prod")).setGeneralMetadata("{ \"email\": \"foo@nih.gov\", \"fullName\" : \"Piotr Rasputin\"}"),
                new User().setConnection(new Connection().setId("nih-gov-prod")).setGeneralMetadata("{ \"email\": \"foobar@nih.gov\", \"fullName\" : \"Matthew Murdock\", \"nih-userid\" : \"NOBODY\"}"),
                new User().setConnection(new Connection().setId("github")).setGeneralMetadata("{ \"email\": \"blablabla@gmail.com\", \"fullName\" : \"Some Girl\"}"),
                new User().setConnection(new Connection().setId("github")).setGeneralMetadata("{ \"email\": \"blablabla@gmail.com\", \"fullName\" : \"Jean Grey\"}"),
                new User().setConnection(new Connection().setId("no-mapping-connection")).setGeneralMetadata("{ \"email\": \"foo@bar.com\", \"fullName\" : \"Luke Cage\"}")
        );
        return allMappings.stream().filter((User user) -> {
            return user.getConnection().getId().equalsIgnoreCase(connectionId.getId());
        }).collect(Collectors.toList());
    }

    private List<UserMetadataMapping> getAllMappingsForConnectionMock(Connection connection) {
        log.info("Mocking mappings for connection with id: " + connection.getId());
        List<UserMetadataMapping> allMappings = List.of(
                new UserMetadataMapping().setConnection(new Connection().setId("ldap-connector")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"),
                new UserMetadataMapping().setConnection(new Connection().setId("nih-gov-prod")).setGeneralMetadataJsonPath("$.nih-userid").setAuth0MetadataJsonPath("$.identities[0].user_id"),
                new UserMetadataMapping().setConnection(new Connection().setId("github")).setGeneralMetadataJsonPath("$.full_name").setAuth0MetadataJsonPath("$.name"),
                new UserMetadataMapping().setConnection(new Connection().setId("github")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.emails[?(@.primary == true)].email"),
                new UserMetadataMapping().setConnection(new Connection().setId("no-user-connection")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"),
                new UserMetadataMapping().setConnection(new Connection().setId("invalid-path")).setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.noPath")

        );
        return allMappings.stream().filter((UserMetadataMapping mapping) -> {
            return mapping.getConnection().getId().equalsIgnoreCase(connection.getId());
        }).collect(Collectors.toList());
    }

    public JsonNode mockAuthAPIUserInfo(String accessToken) throws IOException {
        Map<String, String> map = Map.of("ldap-connector-access-token",
                "{    \"name\": \"Guy,Some\",    \"family_name\": \"Guy\",    \"given_name\": \"Some\",    \"nickname\": \"CH000000000\",    \"groups\": [],    \"emails\": [        \"foo@childrens.harvard.edu\"    ],    \"dn\": \"CN=CH0000000,OU=users,DC=chbdir,DC=org\",    \"distinguishedName\": \"CN=CH0000000,OU=users,DC=chbdir,DC=org\",    \"organizationUnits\": \"CN=CH0000000,OU=users,DC=chbdir,DC=org\",    \"email\": \"foo@childrens.harvard.edu\",    \"updated_at\": \"2018-10-04T18:28:23.371Z\",    \"picture\": \"https://s.gravatar.com/avatar/blablablablablablablablablablablabla?s=480&r=pg&d=https%3A%2F%2Fcdn.auth0.com%2Favatars%2Fsp.png\",    \"user_id\": \"ad|ldap-connector|blablablablablablablablablablablabla\",    \"identities\": [        {            \"user_id\": \"ldap-connector|blablablablablablablablablablablabla\",            \"provider\": \"ad\",            \"connection\": \"ldap-connector\",            \"isSocial\": false        }    ],    \"created_at\": \"2018-01-26T14:06:50.413Z\",    \"username\": \"CH0000000\",    \"app_metadata\": {        \"roles\": [            \"ROLE_CITI_USER\"        ]    },    \"last_ip\": \"134.174.140.32\",    \"last_login\": \"2018-10-04T18:28:23.091Z\",    \"logins_count\": 399,    \"blocked_for\": [],    \"guardian_authenticators\": []}",
                "github-access-token", "{    \"email\": \"blablabla@gmail.com\",    \"name\": \"Some Girl\",    \"picture\": \"https://avatars3.githubusercontent.com/u/0000000000?v=4\",    \"nickname\": \"blablabla\",    \"gravatar_id\": \"\",    \"url\": \"https://api.github.com/users/blablabla\",    \"html_url\": \"https://github.com/blablabla\",    \"followers_url\": \"https://api.github.com/users/blablabla/followers\",    \"following_url\": \"https://api.github.com/users/blablabla/following{/other_user}\",    \"gists_url\": \"https://api.github.com/users/blablabla/gists{/gist_id}\",    \"starred_url\": \"https://api.github.com/users/blablabla/starred{/owner}{/repo}\",    \"subscriptions_url\": \"https://api.github.com/users/blablabla/subscriptions\",    \"organizations_url\": \"https://api.github.com/users/blablabla/orgs\",    \"repos_url\": \"https://api.github.com/users/blablabla/repos\",    \"events_url\": \"https://api.github.com/users/blablabla/events{/privacy}\",    \"received_events_url\": \"https://api.github.com/users/blablabla/received_events\",    \"type\": \"User\",    \"site_admin\": false,    \"location\": \"Nowhere, USA\",    \"hireable\": true,    \"public_repos\": 8,    \"public_gists\": \"0\",    \"followers\": 3,    \"following\": 1,    \"updated_at\": \"2018-09-20T18:47:43.703Z\",    \"emails\": [        {            \"email\": \"blablabla@gmail.com\",            \"primary\": true,            \"verified\": true,            \"visibility\": \"public\"        },        {            \"email\": \"blablabla@users.noreply.github.com\",            \"primary\": false,            \"verified\": true,            \"visibility\": null        }    ],    \"email_verified\": true,    \"user_id\": \"github|0000000\",    \"identities\": [        {            \"provider\": \"github\",            \"user_id\": \"000000000\",            \"connection\": \"github\",            \"isSocial\": true        }    ],    \"created_at\": \"2016-10-22T22:38:20.437Z\",    \"blog\": \"\",    \"node_id\": \"blablabla=\",    \"app_metadata\": {        \"roles\": [            \"ROLE_CITI_USER\"        ]    },    \"last_ip\": \"134.174.140.198\",    \"last_login\": \"2018-09-20T18:47:43.491Z\",    \"logins_count\": 71,    \"blocked_for\": [],    \"guardian_authenticators\": []}",
                "nih-gov-prod-access-token", "{    \"email\": \"NOBODY\",    \"sessionIndex\": \"blablablabla\",    \"UserPrincipalName\": \"\",    \"Mail\": \"\",    \"FirstName\": \"\",    \"LastName\": \"\",    \"MiddleName\": \"\",    \"NEDID\": \"\",    \"nameIdAttributes\": {        \"value\": \"NOBODY\",        \"Format\": \"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\"    },    \"authenticationmethod\": \"urn:oasis:names:tc:SAML:2.0:ac:classes:Password\",    \"issuer\": \"https://auth.nih.gov/IDP\",    \"updated_at\": \"2018-07-23T19:32:51.505Z\",    \"name\": \"\",    \"picture\": \"https://cdn.auth0.com/avatars/default.png\",    \"user_id\": \"samlp|NOBODY\",    \"nickname\": \"\",    \"identities\": [        {            \"user_id\": \"NOBODY\",            \"provider\": \"samlp\",            \"connection\": \"nih-gov-prod\",            \"isSocial\": false        }    ],    \"created_at\": \"2018-04-02T13:10:25.654Z\",    \"app_metadata\": {        \"roles\": [            \"ROLE_CITI_USER\"        ]    },    \"last_ip\": \"134.174.140.195\",    \"last_login\": \"2018-07-23T19:32:51.254Z\",    \"logins_count\": 12,    \"blocked_for\": [],    \"guardian_authenticators\": []}",
                "no-mapping-connection-token", "{    \"email\": \"foo@bar.com\",     \"UserName\": \"foooo\",     \"FirstName\": \"foo\",    \"LastName\": \"oooo\",\"user_id\": \"samlp|fooBar\",    \"identities\": [        {            \"user_id\": \"fooBar\",            \"provider\": \"samlp\",            \"connection\": \"no-mapping-connection\",            \"isSocial\": false        }    ]}",
                "invalid-path-token", "{    \"email\": \"bar@foo.com\",     \"UserName\": \"bahh\",     \"user_id\": \"samlp|barFoo\",    \"identities\": [        {            \"user_id\": \"barFoo\",            \"provider\": \"samlp\",            \"connection\": \"invalid-path\",            \"isSocial\": true        }    ]}",
                "no-user-token", "{    \"email\": \"no@user.com\",     \"UserName\": \"nooooooo\",     \"user_id\": \"samlp|noUser\",    \"identities\": [        {            \"user_id\": \"noUser\",            \"provider\": \"samlp\",            \"connection\": \"no-user-connection\",            \"isSocial\": false        }    ]}"
        );
        String result = map.get(accessToken);
        Map<String, Object> jsonMap = mapper.readValue(result,
                new TypeReference<Map<String, Object>>() {
                });
        return mapper.valueToTree(jsonMap);
    }

    public Optional<Connection> mockConnection(String id) {
        log.info("Mocking connection with id: {}", id);
        return Optional.of(new Connection().setId(id));
    }

}
