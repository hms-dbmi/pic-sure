package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.auth.entity.*;

import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsOverrideRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {JWTUtil.class, UserService.class})
public class UserServiceTest {

    @MockitoBean
    private SecurityContext securityContext;
    @MockitoBean
    private BasicMailService basicMailService;
    @MockitoBean
    private TOSService tosService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private ConnectionRepository connectionRepository;
    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private JWTUtil mockJwtUtil;
    @MockitoBean
    private LoggingClient loggingClient;
    private JWTUtil jwtUtil;

    private static final long defaultTokenExpirationTime = 1000L * 60 * 60; // 1 hour
    private final long longTermTokenExpirationTime = 2592000000L;

    private UserService userService;
    @MockitoBean
    private UserConsentsRepository userConsentsRepository;
    @MockitoBean
    private UserConsentsOverrideRepository userConsentsOverrideRepository;
    @MockitoBean
    private FenceMappingUtility fenceMappingUtility;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        Authentication authentication = mock(Authentication.class);
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);

        jwtUtil = new JWTUtil(generate256Base64Secret(), true);
        userService = new UserService(
            basicMailService, tosService, userRepository, connectionRepository, roleService, userConsentsRepository, userConsentsOverrideRepository, fenceMappingUtility,
            defaultTokenExpirationTime, longTermTokenExpirationTime, mockJwtUtil, "ADMIN,SUPER_ADMIN", null
        );
    }

    @Test
    public void testGetUserProfileResponse() {
        User user = createTestUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(user);

        UserClaims userClaims = buildTestUserClaims(user);
        HashMap<String, String> result = userService.getUserProfileResponse(userClaims);
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

        assertThrows(IllegalArgumentException.class, () -> {
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

        assertThrows(IllegalArgumentException.class, () -> {
            userService.updateUser(List.of(user));
        });
    }

    @Test
    public void testGetUserProfileResponse_missingClaims() {
        UserClaims userClaims = new UserClaims();
        userClaims.setEmail("test@example.com");
        // Missing "sub" - should return null since subject is required
        HashMap<String, String> result = userService.getUserProfileResponse(userClaims);
        assertNull(result);
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
        String token = jwtUtil
            .createJwtToken("whatever", "edu.harvard.hms.dbmi.psama", claims, claims.get("sub").toString(), longTermTokenExpirationTime);
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
        String token = jwtUtil
            .createJwtToken("whatever", "edu.harvard.hms.dbmi.psama", claims, claims.get("sub").toString(), longTermTokenExpirationTime);

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
        String token = jwtUtil
            .createJwtToken("whatever", "edu.harvard.hms.dbmi.psama", claims, claims.get("sub").toString(), longTermTokenExpirationTime);

        Jws<Claims> claimsJws = this.jwtUtil.parseToken(token);

        when(mockJwtUtil.parseToken(anyString())).thenReturn(claimsJws);
        user.setToken(token);
        when(tosService.hasUserAcceptedLatest(any())).thenReturn(true);
        User.UserForDisplay currentUser = userService.getCurrentUser("Bearer " + token, true);
        assertNotNull(currentUser);
        assertEquals(user.getToken(), currentUser.getToken());
    }

    @Test
    public void testGetUserProfileResponse_withoutAcceptedTOS() {
        User user = createTestUser();
        when(tosService.hasUserAcceptedLatest(anyString())).thenReturn(false);

        UserClaims userClaims = buildTestUserClaims(user);
        HashMap<String, String> result = userService.getUserProfileResponse(userClaims);
        assertEquals("false", result.get("acceptedTOS"));
    }

    @Test
    public void testGetUserProfileResponse_withAcceptedTOS() {
        User user = createTestUser();
        when(tosService.hasUserAcceptedLatest(anyString())).thenReturn(true);

        UserClaims userClaims = buildTestUserClaims(user);
        HashMap<String, String> result = userService.getUserProfileResponse(userClaims);
        assertEquals("true", result.get("acceptedTOS"));
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
        String token = jwtUtil
            .createJwtToken("whatever", "edu.harvard.hms.dbmi.psama", claims, claims.get("sub").toString(), longTermTokenExpirationTime);

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

    /**
     * The exact set of role names ensureBaselineRoles is required to ask the database for. Asserting on this set is what catches a baseline
     * role being dropped from the lookup: a role that is never requested is never found, never attached, and nothing else in the method
     * would fail.
     */
    private static final Set<String> EXPECTED_BASELINE_ROLE_NAMES = Set
        .of(RoleService.MANAGED_AUTH_ACCESS_ROLE_NAME, RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME, RoleService.MANAGED_ROLE_NAMED_DATASET);

    /**
     * Every user reaches authorized data through the baseline roles alone now that dbGaP-derived roles are gone. MANUAL_ROLE_AUTH_ACCESS in
     * particular carries USER_CONSENT_ACCESS, so if it stops being attached here the user is silently left with no consent-based access.
     */
    @Test
    public void ensureBaselineRoles_allBaselineRolesExist_allAreAttachedAndPersisted() {
        User user = createTestUser();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleService.findByNames(anySet())).thenReturn(
            Map.of(
                RoleService.MANAGED_AUTH_ACCESS_ROLE_NAME, createRoleNamed(RoleService.MANAGED_AUTH_ACCESS_ROLE_NAME),
                RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME, createRoleNamed(RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME),
                RoleService.MANAGED_ROLE_NAMED_DATASET, createRoleNamed(RoleService.MANAGED_ROLE_NAMED_DATASET)
            )
        );

        User result = userService.ensureBaselineRoles(user);

        assertNotNull(result);
        // Every baseline role must actually be requested. Without this, dropping a name from the lookup set leaves the stub returning all
        // three regardless, and the attachment assertions below stay green while the role is silently lost in production.
        verify(roleService).findByNames(EXPECTED_BASELINE_ROLE_NAMES);
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertEquals(
            Set.of(
                "TEST_ROLE", RoleService.MANAGED_AUTH_ACCESS_ROLE_NAME, RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME,
                RoleService.MANAGED_ROLE_NAMED_DATASET
            ), roleNamesOf(savedUser.getValue()),
            "all three baseline roles must be attached and persisted, alongside the user's pre-existing roles"
        );
    }

    @Test
    public void ensureBaselineRoles_oneBaselineRoleMissing_stillAttachesTheRolesThatWereFound() {
        User user = createTestUser();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // MANUAL_ROLE_NAMED_DATASET is absent from the database; the warn path must not cost the user the other two.
        when(roleService.findByNames(anySet())).thenReturn(
            Map.of(
                RoleService.MANAGED_AUTH_ACCESS_ROLE_NAME, createRoleNamed(RoleService.MANAGED_AUTH_ACCESS_ROLE_NAME),
                RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME, createRoleNamed(RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME)
            )
        );

        User result = userService.ensureBaselineRoles(user);

        assertNotNull(result);
        verify(roleService).findByNames(EXPECTED_BASELINE_ROLE_NAMES);
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertEquals(
            Set.of("TEST_ROLE", RoleService.MANAGED_AUTH_ACCESS_ROLE_NAME, RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME),
            roleNamesOf(savedUser.getValue()),
            "the baseline roles that do exist must still be attached, and the user's pre-existing roles kept"
        );
    }

    private Set<String> roleNamesOf(User user) {
        return user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
    }

    private Role createRoleNamed(String name) {
        Role role = new Role();
        role.setName(name);
        role.setUuid(UUID.randomUUID());
        return role;
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

    @Test
    public void updateUserConsents_newUser() {
        when(fenceMappingUtility.getFENCEMapping()).thenReturn(Map.of("phs1234.c1", new StudyMetaData().setHarmonized(false).setDataType("P")));
        when(userConsentsRepository.findByUserId(any(UUID.class))).thenReturn(null);
        User user = createTestUser();

        userService.updateUserConsents(user, Set.of("phs1234.c1"));

        ArgumentCaptor<UserConsents> userConsentsCaptor = ArgumentCaptor.forClass(UserConsents.class);
        verify(userConsentsRepository).save(userConsentsCaptor.capture());
        assertEquals(Set.of("phs1234.c1"), userConsentsCaptor.getValue().getConsents());
    }


    @Test
    public void updateUserConsents_existingUser() {
        when(fenceMappingUtility.getFENCEMapping()).thenReturn(Map.of("phs1234.c1", new StudyMetaData().setHarmonized(false).setDataType("P")));
        when(userConsentsRepository.findByUserId(any(UUID.class))).thenReturn(new UserConsents().setConsents(Set.of("phs345.c2")));
        User user = createTestUser();

        userService.updateUserConsents(user, Set.of("phs1234.c1"));

        ArgumentCaptor<UserConsents> userConsentsCaptor = ArgumentCaptor.forClass(UserConsents.class);
        verify(userConsentsRepository).save(userConsentsCaptor.capture());
        assertEquals(Set.of("phs1234.c1"), userConsentsCaptor.getValue().getConsents());
    }


    @Test
    public void updateUserConsents_overrideConsents() {
        when(fenceMappingUtility.getFENCEMapping()).thenReturn(Map.of("phs1234.c1", new StudyMetaData().setHarmonized(false).setDataType("P")));
        User user = createTestUser();

        when(userConsentsRepository.findByUserId(user.getUuid())).thenReturn(null);
        UserConsentsOverride userConsentsOverride = new UserConsentsOverride();
        userConsentsOverride.setConsentsOverride(new ConsentsOverride().setConsents(Set.of("phs456.c2"))).setEnabled(true);
        when(userConsentsOverrideRepository.findByUserId(user.getUuid())).thenReturn(userConsentsOverride);


        userService.updateUserConsents(user, Set.of("phs1234.c1"));

        ArgumentCaptor<UserConsents> userConsentsCaptor = ArgumentCaptor.forClass(UserConsents.class);
        verify(userConsentsRepository).save(userConsentsCaptor.capture());
        assertEquals(Set.of("phs456.c2"), userConsentsCaptor.getValue().getConsents());
    }

    @Test
    public void updateUserConsents_overrideConsentsDisabled() {
        when(fenceMappingUtility.getFENCEMapping()).thenReturn(Map.of("phs1234.c1", new StudyMetaData().setHarmonized(false).setDataType("P")));
        User user = createTestUser();

        when(userConsentsRepository.findByUserId(user.getUuid())).thenReturn(null);
        UserConsentsOverride userConsentsOverride = new UserConsentsOverride();
        userConsentsOverride.setConsentsOverride(new ConsentsOverride().setConsents(Set.of("phs456.c2"))).setEnabled(false);
        when(userConsentsOverrideRepository.findByUserId(user.getUuid())).thenReturn(userConsentsOverride);


        userService.updateUserConsents(user, Set.of("phs1234.c1"));

        ArgumentCaptor<UserConsents> userConsentsCaptor = ArgumentCaptor.forClass(UserConsents.class);
        verify(userConsentsRepository).save(userConsentsCaptor.capture());
        assertEquals(Set.of("phs1234.c1"), userConsentsCaptor.getValue().getConsents());
    }


    @Test
    public void getUserConsents_returnsStoredConsentsOfAuthenticatedUser() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        UserConsents stored = new UserConsents().setUserId(user.getUuid()).setConsents(Set.of("phs1234.c1"));
        when(userConsentsRepository.findByUserId(user.getUuid())).thenReturn(stored);

        UserConsents result = userService.getUserConsents();

        assertNotNull(result);
        assertEquals(user.getUuid(), result.getUserId());
        assertEquals(Set.of("phs1234.c1"), result.getConsents());
    }

    @Test
    public void getUserConsents_returnsEmptyConsentsWhenNoRecordStored() {
        User user = createTestUser();
        configureUserSecurityContext(user);
        when(userConsentsRepository.findByUserId(user.getUuid())).thenReturn(null);

        UserConsents result = userService.getUserConsents();

        assertNotNull(result);
        assertEquals(user.getUuid(), result.getUserId());
        assertEquals(Set.of(), result.getConsents());
    }

    @Test
    public void getUserConsents_returnsNullWhenNoAuthenticationPresent() {
        when(securityContext.getAuthentication()).thenReturn(null);

        // Must return null rather than NPE on the unguarded getAuthentication() dereference.
        UserConsents result = assertDoesNotThrow(() -> userService.getUserConsents());

        assertNull(result);
        verify(userConsentsRepository, never()).findByUserId(any(UUID.class));
    }

    @Test
    public void getUserConsents_returnsNullForAnonymousPrincipal() {
        // Spring's anonymous principal is the String "anonymousUser", which is not a CustomUserDetails.
        when(securityContext.getAuthentication())
            .thenReturn(new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        // Must return null rather than ClassCastException on the cast to CustomUserDetails.
        UserConsents result = assertDoesNotThrow(() -> userService.getUserConsents());

        assertNull(result);
        verify(userConsentsRepository, never()).findByUserId(any(UUID.class));
    }

    private UserClaims buildTestUserClaims(User user) {
        UserClaims userClaims = new UserClaims();
        userClaims.setUuid(user.getUuid().toString());
        userClaims.setSub(user.getSubject());
        userClaims.setEmail(user.getEmail());
        userClaims.setName(user.getName());
        return userClaims;
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

        return privilege;
    }

    private void configureUserSecurityContext(User user) {
        CustomUserDetails customUserDetails = new CustomUserDetails(user);
        // configure security context
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
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

}
