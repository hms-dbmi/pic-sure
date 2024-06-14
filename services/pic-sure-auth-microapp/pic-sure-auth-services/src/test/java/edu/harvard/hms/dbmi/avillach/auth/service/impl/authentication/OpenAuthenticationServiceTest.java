package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AccessRuleService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OpenAuthenticationServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private RoleService roleService;

    @Mock
    private AccessRuleService accessRuleService;

    @InjectMocks
    private OpenAuthenticationService openAuthenticationService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testAuthenticate_ValidUUID() {
        UUID uuid = UUID.randomUUID();
        User user = createUser(uuid);
        Map<String, String> authRequest = new HashMap<>();
        authRequest.put("UUID", uuid.toString());

        HashMap<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getSubject());
        claims.put("email", user.getUuid() + "@open_access.com");
        claims.put("uuid", user.getUuid().toString());
        claims.put("expirationDate", new Date().toString());

        when(userService.findUserByUUID(uuid.toString())).thenReturn(user);
        when(userService.getUserProfileResponse(any(Map.class))).thenReturn(claims);

        Map<String, String> authenticate = openAuthenticationService.authenticate(authRequest);
        verify(userService, never()).createOpenAccessUser(any(Role.class));
        verify(userService).findUserByUUID(uuid.toString());
        verify(userService).getUserProfileResponse(any(Map.class));

        assertNotNull(authenticate);
        assertEquals(claims.get("sub"), authenticate.get("sub"));
        assertEquals(claims.get("email"), authenticate.get("email"));
        assertEquals(claims.get("uuid"), authenticate.get("uuid"));
        assertEquals(claims.get("expirationDate"), authenticate.get("expirationDate"));
    }

    @Test
    public void testAuthenticate_InvalidUUID() {
        String invalidUUID = "invalid-uuid";
        Map<String, String> authRequest = new HashMap<>();
        authRequest.put("UUID", invalidUUID);

        when(roleService.getRoleByName(anyString())).thenReturn(createRole());
        when(userService.createOpenAccessUser(any(Role.class))).thenReturn(createUser(UUID.randomUUID()));
        when(userService.getUserProfileResponse(any(Map.class))).thenReturn(new HashMap<>());

        Map<String, String> authenticate = openAuthenticationService.authenticate(authRequest);
        assertNotNull(authenticate);
        assertEquals(0, authenticate.size());
    }

    @Test
    public void testAuthenticate_NoUUID() {
        Map<String, String> authRequest = new HashMap<>();

        when(roleService.getRoleByName(anyString())).thenReturn(createRole());
        when(userService.createOpenAccessUser(any())).thenReturn(createUser(UUID.randomUUID()));
        when(userService.getUserProfileResponse(any(Map.class))).thenReturn(new HashMap<>());

        Map<String, String> authenticate = openAuthenticationService.authenticate(authRequest);
        assertNotNull(authenticate);

        verify(userService).createOpenAccessUser(any(Role.class));
        verify(userService).getUserProfileResponse(any(Map.class));
    }

    private User createUser(UUID uuid) {
        User user = new User();
        user.setUuid(uuid);
        user.setSubject("test_subject");
        user.setEmail(uuid + "@open_access.com");
        return user;
    }

    private Role createRole() {
        Role role = new Role();
        role.setName("open_access_role");
        return role;
    }

}
