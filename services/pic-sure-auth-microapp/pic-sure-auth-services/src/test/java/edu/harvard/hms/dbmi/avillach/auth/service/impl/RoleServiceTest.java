package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.enums.SecurityRoles;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.RasDbgapPermission;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {RoleService.class, PrivilegeService.class})
public class RoleServiceTest {

    @MockBean
    private ApplicationService applicationService;

    @MockBean
    private AccessRuleService accessRuleService;

    @MockBean
    private RoleRepository roleRepository;

    @MockBean
    private PrivilegeRepository privilegeRepo;

    @MockBean
    private SecurityContext securityContext;

    @MockBean
    private Authentication authentication;

    @MockBean
    private FenceMappingUtility fenceMappingUtility;

    @Autowired
    private RoleService roleService;

    @Autowired
    private PrivilegeService privilegeService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    @Test
    public void testGetRoleById_String() {
        UUID roleId = UUID.randomUUID();
        Role role = new Role();
        role.setUuid(roleId);

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        Optional<Role> result = roleService.getRoleById(roleId.toString());

        assertTrue(result.isPresent());
        assertEquals(role, result.get());
        verify(roleRepository, times(1)).findById(roleId);
    }

    @Test
    public void testGetRoleById_UUID() {
        UUID roleId = UUID.randomUUID();
        Role role = new Role();
        role.setUuid(roleId);

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        Optional<Role> result = roleService.getRoleById(roleId);

        assertTrue(result.isPresent());
        assertEquals(role, result.get());
        verify(roleRepository, times(1)).findById(roleId);
    }

    @Test
    public void testGetAllRoles() {
        List<Role> roles = Arrays.asList(new Role(), new Role());

        when(roleRepository.findAll()).thenReturn(roles);

        List<Role> result = roleService.getAllRoles();

        assertEquals(roles, result);
        verify(roleRepository, times(1)).findAll();
    }

    @Test
    public void testAddRoles() {
        List<Role> roles = Arrays.asList(new Role(), new Role());

        when(roleRepository.saveAll(roles)).thenReturn(roles);

        List<Role> result = roleService.addRoles(roles);

        assertEquals(roles, result);
        verify(roleRepository, times(1)).saveAll(roles);
    }

    @Test
    public void testAddRoles_PrivilegeNotFound() {
        Role role = new Role();
        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        role.setPrivileges(new HashSet<>(Collections.singletonList(privilege)));

        when(privilegeRepo.findById(privilege.getUuid())).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> {
            roleService.addRoles(Collections.singletonList(role));
        });
    }

    @Test
    public void testUpdateRoles() {
        List<Role> roles = Arrays.asList(new Role(), new Role());

        when(roleRepository.saveAll(roles)).thenReturn(roles);

        List<Role> result = roleService.updateRoles(roles);

        assertEquals(roles, result);
        verify(roleRepository, times(1)).saveAll(roles);
    }

    @Test
    public void testUpdateRoles_PrivilegeNotFound() {
        Role role = new Role();
        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        role.setPrivileges(new HashSet<>(Collections.singletonList(privilege)));

        when(privilegeRepo.findById(privilege.getUuid())).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> {
            roleService.updateRoles(Collections.singletonList(role));
        });
    }

    @Test
    public void testRemoveRoleById_Success() {
        User user = createTestUser();
        configureUserSecurityContext(user);

        Set<Role> roles = user.getRoles();
        Role role = roles.iterator().next();
        UUID roleId = role.getUuid();

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.findAll()).thenReturn(Collections.singletonList(role));

        Optional<List<Role>> result = roleService.removeRoleById(roleId.toString());

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        verify(roleRepository, times(1)).deleteById(roleId);
        verify(roleRepository, times(1)).findAll();
    }

    @Test
    public void testRemoveRoleById_RoleNotFound() {
        UUID roleId = UUID.randomUUID();

        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        Optional<List<Role>> result = roleService.removeRoleById(roleId.toString());

        assertFalse(result.isPresent());
        verify(roleRepository, never()).deleteById(any());
    }

    @Test
    public void testRemoveRoleById_InsufficientPrivileges() {
        UUID roleId = UUID.randomUUID();
        Role role = new Role();
        role.setUuid(roleId);

        User user = createTestUserWithoutPrivileges();
        configureUserSecurityContext(user);

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        Optional<List<Role>> result = roleService.removeRoleById(roleId.toString());

        assertFalse(result.isPresent());
        verify(roleRepository, never()).deleteById(any());
    }

    @Test
    public void testAddObjectToSet_RoleExists() {
        Set<Role> roles = new HashSet<>();
        Role role = new Role();
        role.setUuid(UUID.randomUUID());

        when(roleRepository.findById(role.getUuid())).thenReturn(Optional.of(role));

        roleService.addObjectToSet(roles, role);

        assertTrue(roles.contains(role));
    }

    @Test
    public void testAddObjectToSet_RoleNotFound() {
        Set<Role> roles = new HashSet<>();
        Role role = new Role();
        role.setUuid(UUID.randomUUID());

        when(roleRepository.findById(role.getUuid())).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> {
            roleService.addObjectToSet(roles, role);
        });
    }

    private Role createTopAdminRole() {
        Role role = new Role();
        role.setName(SecurityRoles.PIC_SURE_TOP_ADMIN.getRole());
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

    private void configureUserSecurityContext(User user) {
        CustomUserDetails customUserDetails = new CustomUserDetails(user);

        // configure security context
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    private User createTestUser() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>(Collections.singleton(createTopAdminRole())));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);

        return user;
    }

    private User createTestUserWithoutPrivileges() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>());
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);
        return user;
    }

    private Role createTopAdminRoleWithoutPrivs() {
        Role role = new Role();
        role.setName(SecurityRoles.PIC_SURE_TOP_ADMIN.getRole());
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.emptySet());
        return role;
    }

    @Test
    public void getRoleNamesForDbgapPermissions_PermissionsAreNull() {
        Optional<Set<String>> rolesForDbgapPermissions = roleService.getRoleNamesForDbgapPermissions(null);
        assertNotNull(rolesForDbgapPermissions);
        assertTrue(rolesForDbgapPermissions.isEmpty());
    }

    @Test
    public void getRoleNamesForDbgapPermissions_PermissionsEmpty() {
        Set<RasDbgapPermission> rasDbgapPermissions = new HashSet<>();

        Optional<Set<String>> rolesForDbgapPermissions = roleService.getRoleNamesForDbgapPermissions(rasDbgapPermissions);
        assertNotNull(rolesForDbgapPermissions);
        assertTrue(rolesForDbgapPermissions.isEmpty());
    }

    @Test
    public void getRoleNamesForDbgapPermissions() {
//      {consentName='General Research Use', phsId='phs000006', version='v1', participantSet='p1', consentGroup='c1', role='pi', expiration=1641013200},
//      RasDbgapPermission{consentName='Exchange Area', phsId='phs000300', version='v1', participantSet='p1', consentGroup='c999', role='pi', expiration=1641013200}
        RasDbgapPermission rasDbgapPermissionV1 = new RasDbgapPermission();
        rasDbgapPermissionV1.setRole("pi");
        rasDbgapPermissionV1.setConsentGroup("c1");
        rasDbgapPermissionV1.setConsentName("General Research Use");
        rasDbgapPermissionV1.setExpiration(1641013200);
        rasDbgapPermissionV1.setParticipantSet("p1");
        rasDbgapPermissionV1.setPhsId("phs000006");
        rasDbgapPermissionV1.setVersion("v1");

        RasDbgapPermission rasDbgapPermissionV2 = new RasDbgapPermission();
        rasDbgapPermissionV2.setRole("pi");
        rasDbgapPermissionV2.setConsentGroup("c1");
        rasDbgapPermissionV2.setConsentName("Exchange Area");
        rasDbgapPermissionV2.setExpiration(1641013200);
        rasDbgapPermissionV2.setParticipantSet("p1");
        rasDbgapPermissionV2.setPhsId("phs000300");
        rasDbgapPermissionV2.setVersion("v1");

        Set<RasDbgapPermission> rasDbgapPermissions = new HashSet<>();
        rasDbgapPermissions.add(rasDbgapPermissionV1);
        rasDbgapPermissions.add(rasDbgapPermissionV2);

        Optional<Set<String>> rolesForDbgapPermissions = roleService.getRoleNamesForDbgapPermissions(rasDbgapPermissions);
        assertNotNull(rolesForDbgapPermissions);
        System.out.println(rolesForDbgapPermissions);
    }

}
