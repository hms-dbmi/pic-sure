package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.enums.SecurityRoles;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CustomUserDetailServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private ApplicationService applicationService;

    @InjectMocks
    private CustomUserDetailService customUserDetailService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void loadUserByUsername_withApplication_success() {
        String applicationName = "testApp";
        Application application = new Application();
        application.setPrivileges(null); // Set the required fields as needed
        when(applicationService.getApplicationByIdWithPrivileges(applicationName)).thenReturn(Optional.of(application));

        UserDetails userDetails = customUserDetailService.loadUserByUsername("application:" + applicationName);
        assertNotNull(userDetails);
        assertTrue(userDetails instanceof CustomApplicationDetails);
    }

    @Test
    public void loadUserByUsername_withApplication_notFound() {
        when(applicationService.getApplicationByIdWithPrivileges("invalidApp")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () ->
                customUserDetailService.loadUserByUsername("application:invalidApp"));
    }

    @Test
    public void loadUserByUsername_withUser_success() {
        String username = "user@example.com";
        User user = new User();
        // Set roles for the user
        Role role = new Role();
        role.setName(SecurityRoles.ADMIN.getRole());
        user.setRoles(Set.of(role));

        Privilege privilege = new Privilege();
        privilege.setName("testPrivilege");
        role.setPrivileges(Set.of(privilege));

        when(userService.findBySubject(username)).thenReturn(user);

        UserDetails userDetails = customUserDetailService.loadUserByUsername(username);
        assertNotNull(userDetails);
        assertTrue(userDetails instanceof CustomUserDetails);
    }

    @Test
    public void loadUserByUsername_withUser_notFound() {
        when(userService.findBySubject("nonexistent@example.com")).thenReturn(null);

        assertThrows(UsernameNotFoundException.class, () ->
                customUserDetailService.loadUserByUsername("nonexistent@example.com"));
    }
}
