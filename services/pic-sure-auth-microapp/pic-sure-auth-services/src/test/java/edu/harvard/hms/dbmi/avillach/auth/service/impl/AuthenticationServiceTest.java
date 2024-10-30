package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.Auth0AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {Auth0AuthenticationService.class})
public class AuthenticationServiceTest {

    @MockBean
    private OauthUserMatchingService matchingService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private BasicMailService basicMailService;
    @MockBean
    private UserService userService;
    @MockBean
    private ConnectionRepository connectionRepository;
    @MockBean
    private RestClientUtil restClientUtil;
    @MockBean
    private CacheEvictionService cacheEvictionService;

    private Auth0AuthenticationService authenticationService;

    private final String accessToken = "dummyAccessToken";
    private final String redirectURI = "http://dummyRedirectUri.com";
    private final String userId = "user123";
    private final String connectionId = "conn123";
    private Map<String, String> authRequest;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        authRequest = new HashMap<>();
        authRequest.put("access_token", accessToken);
        authRequest.put("redirectURI", redirectURI);

        authenticationService = new Auth0AuthenticationService(matchingService, userRepository, basicMailService, userService, connectionRepository, restClientUtil, true, false, "localhost", cacheEvictionService);
    }

    // Tests missing parameters in the authentication request
    @Test
    public void testGetToken_MissingParameters() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> {
            authenticationService.authenticate(new HashMap<>(), "localhost"); // Empty map should trigger the exception
        });
    }

    // Tests the failure in retrieving user information, expecting an IOException to be converted into a NotAuthorizedException
    @Test
    public void testGetToken_UserInfoRetrievalFails() throws IOException {
        when(this.restClientUtil.retrieveGetResponseWithRequestConfiguration(anyString(), any(HttpHeaders.class), anyInt()))
                .thenThrow(new NotAuthorizedException("Failed to retrieve user info"));

        assertThrows(NotAuthorizedException.class, () -> {
            authenticationService.authenticate(authRequest, "localhost");
        });
    }

    // Tests the scenario where the user ID is not found in the user info retrieved
    @Test
    public void testGetToken_NoUserIdInUserInfo() throws IOException {
        when(this.restClientUtil.retrieveGetResponseWithRequestConfiguration(anyString(), any(), anyInt()))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        assertThrows(NotAuthorizedException.class, () -> {
            authenticationService.authenticate(authRequest, "localhost");
        });
    }

    // Tests a successful token retrieval scenario
    @Test
    public void testGetToken_Successful() throws Exception {
        setupSuccessfulTokenRetrievalScenario();

        // return null for matching user
        when(matchingService.matchTokenToUser(any())).thenReturn(null);

        HashMap<String, String> token = authenticationService.authenticate(authRequest, "localhost");
        assertNotNull(token);
    }

    // Additional test to handle retries in user info retrieval
    @Test
    public void testRetrieveUserInfo_WithRetries() throws Exception {
        when(this.restClientUtil.retrieveGetResponseWithRequestConfiguration(anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("Network error"))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));
        // Assuming retrieveUserInfo is accessible, or using reflection if it is private
        JsonNode result = authenticationService.retrieveUserInfo(accessToken);
        assertNotNull(result);
    }

    // Tests matching a token to a user when no existing user is found and an attempt to create a user fails
    @Test
    public void testGetToken_NoUserMatchingAndCreationFails() throws Exception {
        setupNoUserMatchScenario();

        assertThrows(NotAuthorizedException.class, () -> {
            authenticationService.authenticate(authRequest, "localhost");
        });
    }

    // Test scenario where denied access email is triggered
    @Test
    public void testGetToken_SendDeniedAccessEmail() throws Exception {
        setupDeniedEmailScenario();
        this.authenticationService.setDeniedEmailEnabled(true);
        try {
            authenticationService.authenticate(authRequest, "localhost");
        } catch (Exception e) {
            verify(basicMailService).sendDeniedAccessEmail(any());
        }
    }

    private void setupSuccessfulTokenRetrievalScenario() throws IOException {
        this.authenticationService.setDeniedEmailEnabled(false);
        JsonNode mockUserInfo = mock(JsonNode.class);
        when(mockUserInfo.get("user_id")).thenReturn(mock(JsonNode.class));
        when(mockUserInfo.get("user_id").asText()).thenReturn(userId);
        when(mockUserInfo.get("identities")).thenReturn(mock(JsonNode.class));
        when(mockUserInfo.get("identities").get(0)).thenReturn(mock(JsonNode.class));
        when(mockUserInfo.get("identities").get(0).get("connection")).thenReturn(mock(JsonNode.class));
        when(mockUserInfo.get("identities").get(0).get("connection").asText()).thenReturn(connectionId);

        String validJson = "{"
                + "\"user_id\": \"" + userId + "\","
                + "\"identities\": ["
                + "  {\"connection\": \"" + connectionId + "\"}"
                + "]"
                + "}";

        when(restClientUtil.retrieveGetResponseWithRequestConfiguration(anyString(), any(HttpHeaders.class), anyInt()))
                .thenReturn(new ResponseEntity<>(validJson, HttpStatus.OK));

        // Create a test user
        Connection connection = new Connection();
        connection.setId(connectionId);

        User user = new User();
        user.setSubject(userId);
        user.setConnection(connection);
        user.setUuid(UUID.randomUUID());

        when(connectionRepository.findById(anyString())).thenReturn(Optional.of(connection));
        when(matchingService.matchTokenToUser(any())).thenReturn(user);
        when(userRepository.findBySubjectAndConnection(anyString(), any(Connection.class))).thenReturn(user);
        when(userService.getUserProfileResponse(any())).thenReturn(new HashMap<>());
    }

    private void setupNoUserMatchScenario() throws IOException {
        setupSuccessfulTokenRetrievalScenario();

        when(matchingService.matchTokenToUser(any())).thenReturn(null);
        when(userRepository.findBySubjectAndConnection(anyString(), any(Connection.class))).thenReturn(null);
    }

    private void setupDeniedEmailScenario() throws Exception {
        setupNoUserMatchScenario();
        doThrow(new jakarta.mail.MessagingException("Failed sending email")).when(basicMailService).sendDeniedAccessEmail(any());
    }

}