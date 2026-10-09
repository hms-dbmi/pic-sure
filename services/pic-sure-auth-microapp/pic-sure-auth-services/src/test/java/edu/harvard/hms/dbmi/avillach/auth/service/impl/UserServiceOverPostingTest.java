package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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

/**
 * {@code POST} and {@code PATCH /user} cannot reach the fields the login and terms-of-service flows own: {@code subject}, the long-term
 * {@code token}, {@code passport}, {@code acceptedTOS}, {@code matched}, {@code auth0metadata}, or the row identifier on a create. The
 * {@code SUPER_ADMIN} guard still holds on the request-record path.
 */
class UserServiceOverPostingTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final RoleService roleService = mock(RoleService.class);
    private final UserService userService = new UserService(
        mock(BasicMailService.class), mock(TOSService.class), userRepository, connectionRepository, roleService,
        mock(UserConsentsRepository.class), mock(FenceMappingUtility.class), 3600000L, 2592000000L, mock(JWTUtil.class),
        "ADMIN,SUPER_ADMIN", mock(LoggingClient.class), mock(SessionService.class)
    );

    private Role superAdminRole;

    @BeforeEach
    void setUp() {
        superAdminRole = role(AuthNaming.AuthRoleNaming.SUPER_ADMIN);
        authenticateAs(userWithRoles(superAdminRole));
        when(roleService.getRolesByIds(anySet())).thenReturn(Set.of(superAdminRole));
        when(userRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateKeepsTheStoredSubjectTokenPassportAndTosState() {
        User stored = userWithRoles(superAdminRole);
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

        User saved = savedUser();
        assertEquals("okta|real-subject", saved.getSubject());
        assertEquals("stored-long-term-token", saved.getToken());
        assertEquals("stored-passport-jwt", saved.getPassport());
        assertEquals(acceptedTos, saved.getAcceptedTOS());
        assertTrue(saved.isMatched());
        assertEquals("{\"from\":\"idp\"}", saved.getAuth0metadata());
        assertEquals("new@example.com", saved.getEmail());
        assertFalse(saved.isActive());
    }

    @Test
    void updateLeavesTheStoredConnectionAloneWhenTheRequestOmitsIt() {
        User stored = userWithRoles(superAdminRole);
        Connection storedConnection = new Connection().setId("fence").setLabel("Fence");
        stored.setConnection(storedConnection);
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));

        userService.updateFrom(List.of(new UserUpdateRequest(stored.getUuid(), null, null, null, null, null)));

        assertSame(storedConnection, savedUser().getConnection());
    }

    @Test
    void updateResolvesTheConnectionByIdRatherThanTrustingTheRequestCopy() {
        User stored = userWithRoles(superAdminRole);
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));
        Connection persisted = new Connection().setId("fence").setLabel("Persisted label").setSubPrefix("fence|");
        when(connectionRepository.findById("fence")).thenReturn(Optional.of(persisted));

        userService.updateFrom(List.of(new UserUpdateRequest(stored.getUuid(), null, null, null, new ConnectionRef("fence"), null)));

        assertSame(persisted, savedUser().getConnection());
    }

    @Test
    void createNeverCarriesAnIdentifierOrTokenFromTheRequest() {
        userService.createFrom(
            List.of(new UserCreateRequest("new@example.com", true, "{\"email\":\"new@example.com\"}", null, Set.of(idRef(superAdminRole))))
        );

        User saved = savedUser();
        assertNull(saved.getUuid());
        assertNull(saved.getToken());
        assertNull(saved.getPassport());
        assertNull(saved.getSubject());
        assertNull(saved.getAcceptedTOS());
        assertFalse(saved.isMatched());
        assertEquals("new@example.com", saved.getEmail());
    }

    @Test
    void createReadsTheEmailFromGeneralMetadataWhenTheRequestOmitsIt() {
        userService.createFrom(
            List.of(new UserCreateRequest(null, null, "{\"Email\":\"meta@example.com\"}", null, Set.of(idRef(superAdminRole))))
        );

        User saved = savedUser();
        assertEquals("meta@example.com", saved.getEmail());
        assertTrue(saved.isActive());
    }

    @Test
    void updateStillBlocksANonSuperAdminFromGrantingSuperAdmin() {
        Role plainAdmin = role(AuthNaming.AuthRoleNaming.ADMIN);
        authenticateAs(userWithRoles(plainAdmin));
        User stored = userWithRoles(plainAdmin);
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));

        assertThrows(
            IllegalArgumentException.class,
            () -> userService
                .updateFrom(List.of(new UserUpdateRequest(stored.getUuid(), null, null, null, null, Set.of(idRef(superAdminRole)))))
        );
        verify(userRepository, never()).saveAll(anyList());
    }

    @Test
    void createStillBlocksANonSuperAdminFromGrantingSuperAdmin() {
        authenticateAs(userWithRoles(role(AuthNaming.AuthRoleNaming.ADMIN)));

        assertThrows(
            IllegalArgumentException.class,
            () -> userService.createFrom(List.of(new UserCreateRequest("x@example.com", true, "{}", null, Set.of(idRef(superAdminRole)))))
        );
        verify(userRepository, never()).saveAll(anyList());
    }

    @Test
    void updateRejectsAnUnknownUser() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> userService.updateFrom(List.of(new UserUpdateRequest(unknown, "x@example.com", null, null, null, null)))
        );
    }

    @Test
    void updateRejectsAnUnknownConnection() {
        User stored = userWithRoles(superAdminRole);
        when(userRepository.findById(stored.getUuid())).thenReturn(Optional.of(stored));
        when(connectionRepository.findById("no-such-connection")).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> userService.updateFrom(
                List.of(new UserUpdateRequest(stored.getUuid(), null, null, null, new ConnectionRef("no-such-connection"), null))
            )
        );
        verify(userRepository, never()).saveAll(anyList());
    }

    @Test
    void createRejectsAnUnknownConnection() {
        when(connectionRepository.findById("no-such-connection")).thenReturn(Optional.empty());

        ConnectionRef unknown = new ConnectionRef("no-such-connection");
        UserCreateRequest request = new UserCreateRequest("new@example.com", true, null, unknown, Set.of(idRef(superAdminRole)));

        assertThrows(IllegalArgumentException.class, () -> userService.createFrom(List.of(request)));
        verify(userRepository, never()).saveAll(anyList());
    }

    @Test
    void writesReturnNullWhenTheSecurityContextHoldsNoUser() {
        authenticateAs(null);

        assertNull(userService.updateFrom(List.of(new UserUpdateRequest(UUID.randomUUID(), null, null, null, null, null))));
        assertNull(
            userService.createFrom(List.of(new UserCreateRequest("new@example.com", true, null, null, Set.of(idRef(superAdminRole)))))
        );
        verify(userRepository, never()).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private User savedUser() {
        ArgumentCaptor<List<User>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
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

    private static void authenticateAs(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
