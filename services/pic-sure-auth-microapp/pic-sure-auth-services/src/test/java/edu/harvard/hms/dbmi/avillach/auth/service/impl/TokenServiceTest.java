package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.enums.SecurityRoles;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import java.security.SecureRandom;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TokenServiceTest {

    @Mock
    private AuthorizationService authorizationService;

    @Mock
    private UserRepository userRepository;

    private JWTUtil jwtUtil;

    @Mock
    private SecurityContext securityContext;

    private TokenService tokenService;

    private static long testTokenExpiration = 1000L * 60 * 60;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Authentication authentication = mock(Authentication.class);

        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        jwtUtil = new JWTUtil(generate256Base64Secret(), true);
        tokenService = new TokenService(authorizationService, userRepository, 1000L * 60 * 60, jwtUtil);
    }

    @Test
    public void testInspectToken_withValidToken() {
        Application application = createTestApplication();
        configureApplicationSecurityContext(application);

        User user = createTestUser();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );
        System.out.println(token);
        inputMap.put("token", token);

        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertNull(response.get("message"));
        assertTrue((Boolean) response.get("active"));
        assertEquals(user.getSubject(), response.get("sub"));
        assertEquals(application.getPrivileges(), response.get("privileges"));
    }

    @Test
    public void testInspectToken_withNullToken() {
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("token", null);

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertEquals("Token not found", response.get("message"));
    }

    @Test
    public void testInspectToken_withEmptyToken() {
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("token", "");

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertEquals("Token not found", response.get("message"));
    }

    @Test(expected = RuntimeException.class)
    public void testInspectToken_withUserPrincipal() {
        User user = createTestUser();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );
        inputMap.put("token", token);

        configureUserSecurityContext(user);
        Map<String, Object> tokenInspection = tokenService.inspectToken(inputMap);
        assertNotNull(tokenInspection.get("message"));
    }


    @Test
    public void testInspectToken_withValidToken_withNoAssociatedUser() {
        Application application = createTestApplication();
        configureApplicationSecurityContext(application);

        User user = createTestUser();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );
        System.out.println(token);
        inputMap.put("token", token);

        when(userRepository.findBySubject(user.getSubject())).thenReturn(null);

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertEquals("user doesn't exist", response.get("message"));
    }

    @Test(expected = NullPointerException.class)
    public void testInspectToken_withNoApplicationInSecurityContext() {
        configureApplicationSecurityContext(null);

        User user = createTestUser();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );
        System.out.println(token);
        inputMap.put("token", token);

        when(userRepository.findBySubject(user.getSubject())).thenReturn(null);
        tokenService.inspectToken(inputMap);
    }

    /*
    TODO: Investigate if this is a scenario we should be stopping. This test is successful, but the user has
    no token associated with it. The code should at least verify hat the user has the token associated with it
    that is being sent
     */
    @Test
    public void testLongTermInspectToken() {
        Application application = createTestApplication();
        configureApplicationSecurityContext(application);

        User user = createTestUser();
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + claims.get("sub").toString(),
                testTokenExpiration
        );

        inputMap.put("token", token);
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertNull(response.get("message"));
        assertTrue((Boolean) response.get("active"));
        assertEquals(AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + claims.get("sub").toString(), response.get("sub"));
        assertEquals(application.getPrivileges(), response.get("privileges"));
    }

    @Test
    public void testLongTermInspectToken_withUserTokenCompromised() {
        Application application = createTestApplication();
        configureApplicationSecurityContext(application);

        User user = createTestUser();
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + claims.get("sub").toString(),
                testTokenExpiration
        );

        String userToken = jwtUtil.createJwtToken(
                "whatever1", // Different id
                "edu.harvard.hms.dbmi.psama",
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + claims.get("sub").toString(),
                testTokenExpiration
        );
        user.setToken(userToken);

        // User privileges should be a subset of the application's privileges
        application.setPrivileges(user.getTotalPrivilege());
        inputMap.put("token", token);
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertEquals("Cannot find matched long term token, your token might have been refreshed.", response.get("message"));
    }

    @Test
    public void testLongTermInspectToken_withUserRolesNull() {
        Application application = createTestApplication();
        configureApplicationSecurityContext(application);

        User user = createTestUser();
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + claims.get("sub").toString(),
                testTokenExpiration
        );
        user.setToken(token);

        // User privileges should be a subset of the application's privileges
        application.setPrivileges(user.getTotalPrivilege());
        user.setRoles(null);
        inputMap.put("token", token);
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertEquals("User doesn't have enough privileges.", response.get("message"));
    }

    @Test
    public void testLongTermInspectToken_withUserTokenCompromised_() {
        Application application = createTestApplication();
        configureApplicationSecurityContext(application);

        User user = createTestUser();
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        Map<String, Object> inputMap = new HashMap<>();
        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                AuthNaming.LONG_TERM_TOKEN_PREFIX + "|" + claims.get("sub").toString(),
                testTokenExpiration
        );

        user.setToken(token);

        // User privileges should be a subset of the application's privileges
        application.setPrivileges(user.getTotalPrivilege());
        user.setRoles(null);
        inputMap.put("token", token);
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);

        Map<String, Object> response = tokenService.inspectToken(inputMap);
        assertEquals("User doesn't have enough privileges.", response.get("message"));
    }

    @Test
    public void testRefreshToken() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        String authorizationHeader = "Bearer " + token;
        Map<String, String> response = tokenService.refreshToken(authorizationHeader);
        assertNotNull(response.get("token"));
        assertNotNull(response.get("expirationDate"));
    }

    @Test(expected = NotAuthorizedException.class)
    public void testRefreshToken_withoutCustomUserDetailsInAsPrincipal() {
        User user = createTestUser();
//        configureUserSecurityContext(user); // This is commented out to simulate the absence of CustomUserDetails
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        String authorizationHeader = "Bearer " + token;
        tokenService.refreshToken(authorizationHeader);
    }

    @Test
    public void testRefreshToken_withoutUserInCustomUserDetails() {
        User user = createTestUser();
        configureUserSecurityContext(null);
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        String authorizationHeader = "Bearer " + token;
        Map<String, String> response = tokenService.refreshToken(authorizationHeader);
        assertEquals("Inner application error, please contact admin.", response.get("error"));
    }

    @Test
    public void testRefreshToken_withUserWithoutUUID_InCustomUserDetails() {
        User user = createTestUser();
        user.setUuid(null);
        configureUserSecurityContext(user);
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        String authorizationHeader = "Bearer " + token;
        Map<String, String> response = tokenService.refreshToken(authorizationHeader);
        assertEquals("Inner application error, please contact admin.", response.get("error"));
    }

    @Test(expected = NotAuthorizedException.class)
    public void testRefreshToken_whereUserNotExists() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.empty());

        String authorizationHeader = "Bearer " + token;
        tokenService.refreshToken(authorizationHeader);
    }

    @Test(expected = NotAuthorizedException.class)
    public void testRefreshToken_whereUserNotActive() {
        User user = createTestUser();
        user.setActive(false);
        configureUserSecurityContext(user);
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        String authorizationHeader = "Bearer " + token;
        tokenService.refreshToken(authorizationHeader);
    }

    @Test
    public void testRefreshToken_whereUserSubjectNull() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );
        user.setSubject(null);

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        String authorizationHeader = "Bearer " + token;
        Map<String, String> response = tokenService.refreshToken(authorizationHeader);
        assertEquals("Inner application error, please contact admin.", response.get("error"));
    }

    @Test
    public void testRefreshToken_whereUserSubjectHasChanged() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        user.setSubject(user.getSubject());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                testTokenExpiration
        );
        user.setSubject("CHANGED_SUBJECT");

        // User privileges should be a subset of the application's privileges
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        String authorizationHeader = "Bearer " + token;
        Map<String, String> response = tokenService.refreshToken(authorizationHeader);
        assertEquals("Inner application error, try again or contact admin.", response.get("error"));
    }

    private User createTestUser() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(Collections.singleton(createTestRole()));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);

        return user;
    }

    private Role createTestRole() {
        Role role = new Role();
        role.setName(SecurityRoles.ADMIN.name());
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createTestPrivilege()));
        return role;
    }

    private Privilege createTestPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName("TEST_PRIVILEGE");
        privilege.setUuid(UUID.randomUUID());
        return privilege;
    }

    private Application createTestApplication() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("TEST_APPLICATION");
        application.setToken(createValidApplicationTestToken(application));
        application.setPrivileges(new HashSet<>());
        return application;
    }

    private void configureUserSecurityContext(User user) {
        CustomUserDetails customUserDetails = new CustomUserDetails(user);
        // configure security context
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    private void configureApplicationSecurityContext(Application application) {
        CustomApplicationDetails customApplicationDetails = new CustomApplicationDetails(application);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(customApplicationDetails, null, customApplicationDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(mock(jakarta.servlet.http.HttpServletRequest.class)));
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    private String createValidApplicationTestToken(Application application) {
        return this.jwtUtil.createJwtToken(
                null, null,
                new HashMap<>(
                        Map.of(
                                "user_id", AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|" + application.getName()
                        )
                ),
                AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|" + application.getUuid().toString(), 365L * 1000 * 60 * 60 * 24);
    }

    /**
     * Do not use this method in production code. This is only for testing purposes.
     *
     * @return a 256-bit base64 encoded secret
     */
    private String generate256Base64Secret() {
        SecureRandom random = new SecureRandom();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return Base64.getEncoder().encodeToString(secret);
    }
}