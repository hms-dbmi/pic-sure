package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ConnectionRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for {@code POST/PUT /user}. The administrative user endpoints must not let a request body reach the fields the login
 * and terms-of-service flows own: {@code subject}, the long-term {@code token}, {@code passport}, {@code acceptedTOS}, {@code matched},
 * {@code auth0metadata}, and the row identifier itself.
 */
@SpringBootTest
@ContextConfiguration(classes = {JWTUtil.class, UserService.class})
class UserServiceOverPostingTest {

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
    private RoleService roleService;
    @MockBean
    private JWTUtil jwtUtil;
    @MockBean
    private LoggingClient loggingClient;
    @MockBean
    private UserConsentsRepository userConsentsRepository;
    @MockBean
    private FenceMappingUtility fenceMappingUtility;

    private UserService userService;
    private Role adminRole;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContextHolder.setContext(securityContext);
        userService = new UserService(
            basicMailService, tosService, userRepository, connectionRepository, roleService, userConsentsRepository, fenceMappingUtility,
            3600000L, 2592000000L, jwtUtil, "ADMIN,SUPER_ADMIN", null
        );

        adminRole = role(AuthNaming.AuthRoleNaming.SUPER_ADMIN);
        authenticateAs(userWithRoles(adminRole));
        doAnswer(invocation -> Set.of(adminRole)).when(roleService).getRolesByIds(anySet());
        when(userRepository.saveAll(anyUsers())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void updateKeepsTheStoredSubjectTokenPassportAndTosState() {
        User stored = userWithRoles(adminRole);
        Date acceptedTos = new Date(1_700_000_000_000L);
        stored.setSubject("okta|real-subject");
        stored.setToken("stored-long-term-token");
        stored.setPassport("stored-passport-jwt");
        stored.setAcceptedTOS(acceptedTos);
        stored.setMatched(true);
        stored.setAuth0metadata("{\"from\":\"idp\"}");
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));

        userService.updateFrom(
            List.of(new UserUpdateRequest(stored.getUuid(), "new@example.com", false, "{\"email\":\"new@example.com\"}", null, null))
        );

        User saved = capturedSavedUser();
        assertEquals("okta|real-subject", saved.getSubject(), "subject is identity-provider owned");
        assertEquals("stored-long-term-token", saved.getToken(), "the long-term token is server-minted");
        assertEquals("stored-passport-jwt", saved.getPassport(), "the passport comes from RAS, not a request body");
        assertEquals(acceptedTos, saved.getAcceptedTOS(), "terms-of-service acceptance is not client-settable");
        assertEquals(true, saved.isMatched(), "matching state is set by the matching service");
        assertEquals("{\"from\":\"idp\"}", saved.getAuth0metadata(), "auth0 metadata is identity-provider owned");
        assertEquals("new@example.com", saved.getEmail(), "the allowlisted email is applied");
        assertFalse(saved.isActive(), "the allowlisted active flag is applied");
    }

    @Test
    void updateLeavesTheStoredConnectionAloneWhenTheRequestOmitsIt() {
        User stored = userWithRoles(adminRole);
        Connection storedConnection = new Connection().setId("fence").setLabel("Fence");
        stored.setConnection(storedConnection);
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));

        userService.updateFrom(List.of(new UserUpdateRequest(stored.getUuid(), null, null, null, null, null)));

        assertSame(storedConnection, capturedSavedUser().getConnection());
    }

    @Test
    void updateResolvesTheConnectionByIdRatherThanTrustingTheRequestCopy() {
        User stored = userWithRoles(adminRole);
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));
        Connection persisted = new Connection().setId("fence").setLabel("Persisted label").setSubPrefix("fence|");
        when(connectionRepository.findById("fence")).thenReturn(Optional.of(persisted));

        userService.updateFrom(List.of(new UserUpdateRequest(stored.getUuid(), null, null, null, new ConnectionRef("fence"), null)));

        assertSame(persisted, capturedSavedUser().getConnection());
    }

    @Test
    void createNeverCarriesAnIdentifierOrTokenFromTheRequest() {
        userService.createFrom(
            List.of(new UserCreateRequest("new@example.com", true, "{\"email\":\"new@example.com\"}", null, Set.of(idRef(adminRole))))
        );

        User saved = capturedSavedUser();
        assertNull(saved.getUuid(), "the identifier is generated on persist, so a create cannot target an existing row");
        assertNull(saved.getToken(), "a created user has no long-term token until one is minted");
        assertNull(saved.getPassport());
        assertNull(saved.getSubject());
        assertNull(saved.getAcceptedTOS());
        assertFalse(saved.isMatched());
        assertEquals("new@example.com", saved.getEmail());
    }

    @Test
    void updateStillBlocksANonSuperAdminFromGrantingSuperAdmin() {
        Role plainAdmin = role(AuthNaming.AuthRoleNaming.ADMIN);
        authenticateAs(userWithRoles(plainAdmin));
        User stored = userWithRoles(plainAdmin);
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));
        doAnswer(invocation -> Set.of(adminRole)).when(roleService).getRolesByIds(anySet());

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> userService.updateFrom(List.of(new UserUpdateRequest(stored.getUuid(), null, null, null, null, Set.of(idRef(adminRole)))))
        );

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).saveAll(anyUsers());
    }

    @Test
    void updateRejectsAnUnknownUser() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> userService.updateFrom(List.of(new UserUpdateRequest(unknown, "x@example.com", null, null, null, null)))
        );
    }

    private User capturedSavedUser() {
        ArgumentCaptor<List<User>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(userRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<User> anyUsers() {
        return org.mockito.ArgumentMatchers.anyList();
    }

    private static EntityIdRef idRef(Role role) {
        return new EntityIdRef(role.getUuid());
    }

    private static User userWithRoles(Role... roles) {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>(Set.of(roles)));
        return user;
    }

    private static Role role(String privilegeName) {
        Privilege privilege = new Privilege();
        privilege.setName(privilegeName);
        privilege.setUuid(UUID.randomUUID());
        Role role = new Role();
        role.setName(privilegeName);
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Set.of(privilege));
        return role;
    }

    private void authenticateAs(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        when(securityContext.getAuthentication())
            .thenReturn(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
