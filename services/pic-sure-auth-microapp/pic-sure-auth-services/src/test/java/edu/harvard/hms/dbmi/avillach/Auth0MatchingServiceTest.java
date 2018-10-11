package edu.harvard.hms.dbmi.avillach;

import com.auth0.exception.Auth0Exception;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.UserMetadataMapping;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.Auth0UserMatchingService;
import edu.harvard.hms.dbmi.avillach.auth.service.UserMetadataMappingService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class Auth0MatchingServiceTest {

    @Mock
    UserRepository userRepo = mock(UserRepository.class);

    @Mock
    UserMetadataMappingService mappingService = mock(UserMetadataMappingService.class);

    @InjectMocks
    Auth0UserMatchingService cut = new Auth0UserMatchingService();

    User persistedUser;

    @Before
    public void setUp() throws Auth0Exception {
        MockitoAnnotations.initMocks(this);
        doAnswer(invocation -> (listUnmatchedByConnectionIdMock(invocation.getArgument(0)))).
                when(userRepo).listUnmatchedByConnectionId(any());
        doAnswer(invocation -> (getAllMappingsForConnectionMock(invocation.getArgument(0)))).
                when(mappingService).getAllMappingsForConnection(any());
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                persistedUser = invocation.getArgument(0);
                return null;
            }
        }).when(userRepo).persist(any(User.class));
    }

    @Test
    public void testMatchTokenToUser() {
        String ldapToken = "ldap-connector-access-token";
        String githubToken = "github-access-token";
        String nihToken = "nih-gov-prod-access-token";

        try {
            //NOTE: These string values come from mockAuthAPIUserInfo in Auth0UserMatchingService.  Do not make changes here or there without updating to match or the tests will fail.

            //Test when everything works fine
            User result = cut.matchTokenToUser(ldapToken);
            assertNotNull(result);
            assertNotNull(result.getAuth0metadata());
            assertNotNull(result.getUserId());
            assertEquals("ad|ldap-connector|blablablablablablablablablablablabla", result.getUserId());
            assertTrue(result.isMatched());
            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertEquals(persistedUser.getAuth0metadata(), result.getAuth0metadata());
            assertNotNull(persistedUser.getUserId());
            assertEquals("ad|ldap-connector|blablablablablablablablablablablabla", persistedUser.getUserId());
            assertTrue(persistedUser.isMatched());

            //Reset
            persistedUser = null;

            //Test when multiple mappings in database
            result = cut.matchTokenToUser(githubToken);
            assertNotNull(result);
            assertNotNull(result.getAuth0metadata());
            assertNotNull(result.getUserId());
            assertEquals("github|0000000", result.getUserId());
            assertTrue(result.isMatched());
            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertEquals(persistedUser.getAuth0metadata(), result.getAuth0metadata());
            assertNotNull(persistedUser.getUserId());
            assertEquals("github|0000000", persistedUser.getUserId());
            assertTrue(persistedUser.isMatched());

            persistedUser = null;

            //Test when path not found in user generalmetadata
            result = cut.matchTokenToUser(nihToken);
            assertNotNull(result);
            assertNotNull(result.getAuth0metadata());
            assertNotNull(result.getUserId());
            assertEquals("samlp|NOBODY", result.getUserId());
            assertTrue(result.isMatched());
            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertEquals(persistedUser.getAuth0metadata(), result.getAuth0metadata());
            assertNotNull(persistedUser.getUserId());
            assertEquals("samlp|NOBODY", persistedUser.getUserId());
            assertTrue(persistedUser.isMatched());

            persistedUser = null;

            //Test when no user matches
            result = cut.matchTokenToUser("no-user-token");
            assertNull(result);

            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertNotNull(persistedUser.getUserId());
            assertEquals("samlp|noUser", persistedUser.getUserId());
            assertFalse(persistedUser.isMatched());

            persistedUser = null;

            //Test when path not found in auth0metadata -- This is a problem with the mapping data in the database
            result = cut.matchTokenToUser("invalid-path-token");
            assertNull(result);

            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertNotNull(persistedUser.getUserId());
            assertEquals("samlp|barFoo", persistedUser.getUserId());
            assertFalse(persistedUser.isMatched());

            persistedUser = null;

            //Test when no mappings in database -- We have no mappings set up for this yet
            result = cut.matchTokenToUser("no-mapping-connection-token");
            assertNull(result);
            assertNotNull(persistedUser);
            assertNotNull(persistedUser.getAuth0metadata());
            assertNotNull(persistedUser.getUserId());
            assertEquals("samlp|fooBar", persistedUser.getUserId());
            assertFalse(persistedUser.isMatched());

            persistedUser = null;

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
                new UserMetadataMapping().setConnectionId("ldap-connector").setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"),
                new UserMetadataMapping().setConnectionId("nih-gov-prod").setGeneralMetadataJsonPath("$.nih-userid").setAuth0MetadataJsonPath("$.identities[0].user_id"),
                new UserMetadataMapping().setConnectionId("github").setGeneralMetadataJsonPath("$.full_name").setAuth0MetadataJsonPath("$.name"),
                new UserMetadataMapping().setConnectionId("github").setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.emails[?(@.primary == true)].email"),
                new UserMetadataMapping().setConnectionId("no-user-connection").setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.email"),
                new UserMetadataMapping().setConnectionId("invalid-path").setGeneralMetadataJsonPath("$.email").setAuth0MetadataJsonPath("$.noPath")
                );
        return allMappings.stream().filter((UserMetadataMapping mapping) -> {
            return mapping.getConnectionId().equalsIgnoreCase(connectionId);
        }).collect(Collectors.toList());
    }

}
