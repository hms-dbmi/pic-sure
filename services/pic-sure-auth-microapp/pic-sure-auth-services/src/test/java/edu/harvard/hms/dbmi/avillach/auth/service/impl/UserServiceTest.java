package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;

import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {JWTUtil.class, UserService.class})
public class UserServiceTest {

    @MockBean
    private SecurityContext securityContext;
    @MockBean
    private BasicMailService basicMailService;
    @MockBean
    private TOSService tosService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private ConnectionRepository connectionRepository;
    @MockBean
    private ApplicationRepository applicationRepository;
    @MockBean
    private RoleService roleService;
    @MockBean
    private JWTUtil mockJwtUtil;
    private JWTUtil jwtUtil;

    private static final long defaultTokenExpirationTime = 1000L * 60 * 60; // 1 hour
    private final long longTermTokenExpirationTime = 2592000000L;

    private UserService userService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        Authentication authentication = mock(Authentication.class);
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);

        jwtUtil = new JWTUtil(generate256Base64Secret(), true);
        String applicationUUID = UUID.randomUUID().toString();
        userService = new UserService(
                basicMailService,
                tosService,
                userRepository,
                connectionRepository,
                applicationRepository,
                roleService,
                defaultTokenExpirationTime,
                applicationUUID,
                longTermTokenExpirationTime,
                mockJwtUtil,
                false);
    }

    @Test
    public void testGetUserProfileResponse() {
        User user = createTestUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(user);

        HashMap<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("sub", user.getSubject());

        HashMap<String, String> result = userService.getUserProfileResponse(claims);
        assertNotNull(result);
    }

    @Test
    public void testGetUserById_found() {
        UUID testId = UUID.randomUUID();
        User user = createTestUser();
        user.setUuid(testId);

        when(userRepository.findById(testId)).thenReturn(Optional.of(user));

        User result = userService.getUserById(testId.toString());
        assertNotNull(result);
        assertEquals(testId, result.getUuid());
    }

    @Test
    public void testGetUserById_notFound() {
        UUID testId = UUID.randomUUID();
        when(userRepository.findById(testId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, ()-> {
            userService.getUserById(testId.toString());
        });
    }

    @Test
    public void testGetAllUsers() {
        User user = createTestUser();
        when(userRepository.findAll()).thenReturn(List.of(user));

        Iterable<User> result = userService.getAllUsers();
        assertNotNull(result);
        assertTrue(result.iterator().hasNext());
    }

    @Test
    public void testAddUser() {
        User user = createTestUser();
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        List<User> result = userService.addUser(List.of(user));
        assertNotNull(result);
    }

    @Test
    public void testAddUsers() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        List<User> result = userService.addUsers(List.of(user));
        System.out.println(result);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(user, result.getFirst());
    }

    @Test
    public void testAddUsers_SuperAdminRole() {
        User user = createTestUser();
        user.getRoles().add(createSuperAdminRole());
        configureUserSecurityContext(user);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        List<User> result = userService.addUsers(List.of(user));
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(user, result.getFirst());
    }

    @Test
    public void testAddUsers_SuperAdminRole_withoutNecessaryPrivileges() {
        User user = createTestUser();
        Set<Role> roles = user.getRoles();
        roles.add(createSuperAdminRole());
        user.setRoles(roles);

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        System.out.println(user.getRoles());
        User loggedInUser = createTestUser();
        configureUserSecurityContext(loggedInUser);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        assertThrows(IllegalArgumentException.class, () -> {
            userService.addUsers(List.of(user));
        });

    }

    @Test
    public void testAddUsers_UserRoleNotExisting() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        assertThrows(RuntimeException.class, () -> {
            List<User> result = userService.addUsers(List.of(user));
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals(user, result.getFirst());
        });
    }

    @Test
    public void testAddUsers_UserEmailNull_AndBadMetadata() {
        User user = createTestUser();
        configureUserSecurityContext(user);

        // set email to null
        user.setEmail(null);
        // set bad metadata
        user.setGeneralMetadata("bad metadata");

        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));
        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        userService.addUsers(List.of(user));
    }

    @Test
    public void testAddUsers_UserEmailNull_AndValidMetadata() {
        User user = createTestUser();
        configureUserSecurityContext(user);

        // set email to null
        user.setEmail(null);
        // set bad metadata
        user.setGeneralMetadata("{\"email\":\" " + user.getEmail() + "\"}");

        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        userService.addUsers(List.of(user));
    }

    @Test
    public void testUpdateUser() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        List<User> result = userService.updateUser(List.of(user));
        assertNotNull(result);
    }

    @Test
    public void testUpdateUser_SuperAdminRole() {
        User user = createTestUser();
        user.getRoles().add(createSuperAdminRole());
        configureUserSecurityContext(user);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));

        List<User> result = userService.updateUser(List.of(user));
        assertNotNull(result);
    }

    @Test
    public void testUpdateUser_SuperAdminRole_withoutNecessaryPrivileges() {
        User user = createTestUser();
        Set<Role> roles = user.getRoles();
        roles.add(createSuperAdminRole());
        user.setRoles(roles);

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        User loggedInUser = createTestUser();
        configureUserSecurityContext(loggedInUser);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        User userToFindByID = new User();
        userToFindByID.setUuid(user.getUuid());
        userToFindByID.setRoles(new HashSet<>());
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(userToFindByID));

        assertThrows(IllegalArgumentException.class, ()-> {
            userService.updateUser(List.of(user));
        });
    }

    @Test
    public void testGetUserProfileResponse_missingClaims() {
        HashMap<String, Object> incompleteClaims = new HashMap<>();
        incompleteClaims.put("email", "test@example.com");
        // Missing "sub" which is mandatory for the method logic
        assertThrows(NullPointerException.class, ()->{
            userService.getUserProfileResponse(incompleteClaims);
        });
    }

    @Test
    public void testGetUserById_invalidUUID() {
        assertThrows(IllegalArgumentException.class, () -> {
            userService.getUserById("not-a-real-uuid");
        });
    }

    @Test
    public void testSendUserUpdateEmails_success() throws MessagingException {
        User user = createTestUser();
        List<User> users = List.of(user);
        when(userRepository.saveAll(users)).thenReturn(users);
        configureUserSecurityContext(user);

        try {
            userService.sendUserUpdateEmailsFromResponse(users);
        } catch (Exception e) {
            fail("Should not throw an exception when sending emails");
        }

        verify(basicMailService).sendUsersAccessEmail(user);
    }

    @Test
    public void testGetCurrentUser() {
        User user = createTestUser();


        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                longTermTokenExpirationTime
        );
        user.setToken(token);
        configureUserSecurityContext(user);

        Jws<Claims> claimsJws = this.jwtUtil.parseToken(token);
        System.out.println(claimsJws);

        when(mockJwtUtil.parseToken(anyString())).thenReturn(claimsJws);
        when(tosService.hasUserAcceptedLatest(any())).thenReturn(true);
        User.UserForDisplay currentUser = userService.getCurrentUser("Bearer " + token, true);
        assertNotNull(currentUser);
        assertEquals(user.getToken(), currentUser.getToken());
    }

    @Test
    public void testGetCurrentUser_withNoToken() {
        User user = createTestUser();
        configureUserSecurityContext(user);

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                longTermTokenExpirationTime
        );

        Jws<Claims> claimsJws = this.jwtUtil.parseToken(token);
        System.out.println(claimsJws);
        when(mockJwtUtil.parseToken(anyString())).thenReturn(claimsJws);
        when(tosService.hasUserAcceptedLatest(any())).thenReturn(true);
        User.UserForDisplay currentUser = userService.getCurrentUser("Bearer " + token, true);
        assertNotNull(currentUser);
        assertEquals(user.getToken(), currentUser.getToken());
    }

    @Test
    public void testGetCurrentUser_noUserInContext() {
        when(securityContext.getAuthentication()).thenReturn(null);

        CustomUserDetails customUserDetails = new CustomUserDetails(null);
        // configure security context
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
        when(securityContext.getAuthentication()).thenReturn(authentication);

        assertNull(userService.getCurrentUser("Bearer some-token", true));
    }

    @Test
    public void testGetCurrentUser_withNoPrivileges() {
        User user = createTestUser();
        user.setRoles(new HashSet<>());
        configureUserSecurityContext(user);

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                longTermTokenExpirationTime
        );

        Jws<Claims> claimsJws = this.jwtUtil.parseToken(token);

        when(mockJwtUtil.parseToken(anyString())).thenReturn(claimsJws);
        user.setToken(token);
        when(tosService.hasUserAcceptedLatest(any())).thenReturn(true);
        User.UserForDisplay currentUser = userService.getCurrentUser("Bearer " + token, true);
        assertNotNull(currentUser);
        assertEquals(user.getToken(), currentUser.getToken());
    }

    @Test
    public void testGetQueryTemplate_invalidApplicationId() {
        assertThrows(IllegalArgumentException.class, ()->{
            userService.getQueryTemplate(null);
        });
    }

    @Test
    public void testGetUserProfileResponse_withoutAcceptedTOS() {
        User user = createTestUser();
        when(tosService.hasUserAcceptedLatest(anyString())).thenReturn(false);

        HashMap<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("sub", user.getSubject());

        HashMap<String, String> result = userService.getUserProfileResponse(claims);
        assertEquals("false", result.get("acceptedTOS"));
    }

    @Test
    public void testGetUserProfileResponse_withAcceptedTOS() {
        User user = createTestUser();
        when(tosService.hasUserAcceptedLatest(anyString())).thenReturn(true);

        HashMap<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("sub", user.getSubject());

        HashMap<String, String> result = userService.getUserProfileResponse(claims);
        assertEquals("true", result.get("acceptedTOS"));
    }

    @Test
    public void testGetQueryTemplate_validApplicationId() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        Application application = createTestApplication();


        when(applicationRepository.findById(any(UUID.class))).thenReturn(Optional.of(application));
        when(applicationRepository.findById(application.getUuid())).thenReturn(Optional.of(application));

        Optional<String> result = userService.getQueryTemplate(application.getUuid().toString());
        assertTrue(result.isPresent());
    }

    @Test
    public void testGetDefaultQueryTemplate() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        Application application = createTestApplication();

        when(applicationRepository.findById(any(UUID.class))).thenReturn(Optional.of(application));
        when(applicationRepository.findById(application.getUuid())).thenReturn(Optional.of(application));

        Map<String, String> result = userService.getDefaultQueryTemplate();
        assertTrue(result.containsKey("queryTemplate"));
        assertNotNull(result.get("queryTemplate"));
    }

    @Test
    public void testAddUsers_withUserHavingEmptyRoleSet() {
        User user = createTestUser();
        user.setRoles(null);

        configureUserSecurityContext(user);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        assertThrows(IllegalArgumentException.class, () -> {
            List<User> result = userService.addUsers(List.of(user));
            assertNull(result.getFirst().getRoles());
        });
    }

    @Test
    public void testUpdateUser_withNoChanges() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        when(userRepository.findById(user.getUuid())).thenReturn(Optional.of(user));
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        doAnswer(invocation -> new HashSet<>(user.getRoles())).when(roleService).getRolesByIds(anySet());

        List<User> result = userService.updateUser(List.of(user));
        assertEquals(user, result.getFirst());
    }

    @Test
    public void testRefreshUserToken() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        when(userRepository.saveAll(List.of(user))).thenReturn(List.of(user));

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());

        // Application Long term token
        String token = jwtUtil.createJwtToken(
                "whatever",
                "edu.harvard.hms.dbmi.psama",
                claims,
                claims.get("sub").toString(),
                longTermTokenExpirationTime
        );

        String authorizationHeader = "Bearer " + token;
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", authorizationHeader);

        Jws<Claims> claimsJws = this.jwtUtil.parseToken(token);
        when(mockJwtUtil.parseToken(anyString())).thenReturn(claimsJws);
        when(mockJwtUtil.createJwtToken(anyString(), anyString(), anyMap(), anyString(), anyLong())).thenReturn(token);

        Map<String, String> result = userService.refreshUserToken(headers);
        assertNotNull(result);
        assertEquals(token, result.get("userLongTermToken"));
    }

    @Test
    public void testChangeRole() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        when(userRepository.save(user)).thenReturn(user);

        // new roles
        Role role = createTestRole();
        role.setName("NEW_ROLE");

        Set<Role> newRoles = new HashSet<>();
        newRoles.add(role);

        User user1 = userService.changeRole(user, newRoles);
        assertNotNull(user1);

        assertEquals(newRoles, user1.getRoles());
    }

    @Test
    public void testFindUserBySubject() {
        User user = createTestUser();
        when(userRepository.findBySubject(user.getSubject())).thenReturn(user);

        User result = userService.findBySubject(user.getSubject());
        assertNotNull(result);
        assertEquals(user, result);
    }

    @Test
    public void testSaveUser() {
        User user = createTestUser();
        when(userRepository.save(user)).thenReturn(user);

        User result = userService.save(user);
        assertNotNull(result);
        assertEquals(user, result);
    }

    private User createTestUser() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>(Collections.singleton(createTestRole())));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);

        return user;
    }

    private Application createTestApplication() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("TEST_APPLICATION");
        application.setToken(createValidApplicationTestToken(application));
        application.setPrivileges(new HashSet<>());
        return application;
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

    private Role createTestRole() {
        Role role = new Role();
        role.setName("TEST_ROLE");
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createTestPrivilege()));
        return role;
    }

    private Privilege createTestPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName("TEST_PRIVILEGE");
        privilege.setUuid(UUID.randomUUID());
        privilege.setQueryTemplate(createQueryTemplate("consent_concept_path_"+privilege.getUuid(), "project_name_"+privilege.getUuid(), "consent_group_"+privilege.getUuid()));

        return privilege;
    }

    private void configureUserSecurityContext(User user) {
        CustomUserDetails customUserDetails = new CustomUserDetails(user);
        // configure security context
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    private Role createSuperAdminRole() {
        Role role = new Role();
        role.setName(AuthNaming.AuthRoleNaming.SUPER_ADMIN);
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createSuperAdminPrivilege()));
        return role;
    }

    private Privilege createSuperAdminPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName(AuthNaming.AuthRoleNaming.SUPER_ADMIN);
        privilege.setUuid(UUID.randomUUID());
        return privilege;
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

    private String createQueryTemplate(String consent_concept_path, String project_name, String consent_group) {
    	return "{\"categoryFilters\": {\""
                + consent_concept_path
                + "\":\""
                + project_name + "." + consent_group
                + "\"},"
                + "\"numericFilters\":{},\"requiredFields\":[],"
                + "\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],"
                + "\"expectedResultType\": \"COUNT\""
                + "}";
    }

}