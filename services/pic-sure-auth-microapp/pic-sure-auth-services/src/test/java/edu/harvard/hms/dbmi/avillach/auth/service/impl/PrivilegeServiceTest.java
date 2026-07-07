package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {PrivilegeService.class})
public class PrivilegeServiceTest {

    @MockBean
    private PrivilegeRepository privilegeRepository;

    @MockBean
    private SecurityContext securityContext;

    @MockBean
    private Authentication authentication;

    @MockBean
    private ApplicationService applicationService;

    @MockBean
    private AccessRuleService accessRuleService;

    @Autowired
    private PrivilegeService privilegeService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    @Test
    public void testDeletePrivilegeByPrivilegeId_Success() {
        UUID privilegeId = UUID.randomUUID();
        Privilege privilege = new Privilege();
        privilege.setUuid(privilegeId);
        privilege.setName("USER_PRIVILEGE");

        when(privilegeRepository.findById(privilegeId)).thenReturn(Optional.of(privilege));
        when(authentication.getName()).thenReturn("testUser");

        List<Privilege> privileges = Arrays.asList(new Privilege(), new Privilege());
        when(privilegeRepository.findAll()).thenReturn(privileges);

        List<Privilege> result = privilegeService.deletePrivilegeByPrivilegeId(privilegeId.toString());

        assertEquals(privileges, result);
        verify(privilegeRepository, times(1)).deleteById(privilegeId);
        verify(privilegeRepository, times(2)).findAll();
    }

    @Test
    public void testDeletePrivilegeByPrivilegeId_AdminPrivilege() {
        UUID privilegeId = UUID.randomUUID();
        Privilege privilege = new Privilege();
        privilege.setUuid(privilegeId);
        privilege.setName(ADMIN);

        when(privilegeRepository.findById(privilegeId)).thenReturn(Optional.of(privilege));
        when(authentication.getName()).thenReturn("testUser");

        assertThrows(RuntimeException.class, () -> {
            privilegeService.deletePrivilegeByPrivilegeId(privilegeId.toString());
        });
    }

    @Test
    public void testUpdatePrivileges() {
        List<Privilege> privileges = Arrays.asList(new Privilege(), new Privilege());

        when(privilegeRepository.saveAll(privileges)).thenReturn(privileges);
        when(privilegeRepository.findAll()).thenReturn(privileges);

        List<Privilege> result = privilegeService.updatePrivileges(privileges);

        assertEquals(privileges, result);
        verify(privilegeRepository, times(1)).saveAll(privileges);
        verify(privilegeRepository, times(1)).findAll();
    }

    @Test
    public void testAddPrivileges() {
        List<Privilege> privileges = Arrays.asList(new Privilege(), new Privilege());

        when(privilegeRepository.saveAll(privileges)).thenReturn(privileges);

        List<Privilege> result = privilegeService.addPrivileges(privileges);

        assertEquals(privileges, result);
        verify(privilegeRepository, times(1)).saveAll(privileges);
    }

    @Test
    public void testGetPrivilegesAll() {
        List<Privilege> privileges = Arrays.asList(new Privilege(), new Privilege());

        when(privilegeRepository.findAll()).thenReturn(privileges);

        List<Privilege> result = privilegeService.getPrivilegesAll();

        assertEquals(privileges, result);
        verify(privilegeRepository, times(1)).findAll();
    }

    @Test
    public void testGetPrivilegeById_Found() {
        UUID privilegeId = UUID.randomUUID();
        Privilege privilege = new Privilege();
        privilege.setUuid(privilegeId);

        when(privilegeRepository.findById(privilegeId)).thenReturn(Optional.of(privilege));

        Privilege result = privilegeService.getPrivilegeById(privilegeId.toString());

        assertEquals(privilege, result);
        verify(privilegeRepository, times(1)).findById(privilegeId);
    }

    @Test
    public void testGetPrivilegeById_NotFound() {
        UUID privilegeId = UUID.randomUUID();

        when(privilegeRepository.findById(privilegeId)).thenReturn(Optional.empty());

        Privilege result = privilegeService.getPrivilegeById(privilegeId.toString());

        assertNull(result);
        verify(privilegeRepository, times(1)).findById(privilegeId);
    }
}
