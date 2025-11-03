package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;

import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@ContextConfiguration(classes = {CustomUserDetailService.class})
public class CustomUserDetailServiceTest {

    @MockBean
    private UserService userService;

    @MockBean
    private ApplicationService applicationService;

    @Autowired
    private CustomUserDetailService customUserDetailService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void loadUserByUsername_withApplication_success() {
        String applicationName = "testApp";
        Application application = new Application();
        application.setPrivileges(null); // Set the required fields as needed
        when(applicationService.getApplicationByIdWithPrivileges(applicationName)).thenReturn(Optional.of(application));

        UserDetails userDetails = customUserDetailService.loadUserByUsername("application:" + applicationName);
        assertNotNull(userDetails);
        assertInstanceOf(CustomApplicationDetails.class, userDetails);
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
        role.setUuid(java.util.UUID.randomUUID());
        role.setName(AuthNaming.AuthRoleNaming.ADMIN);
        user.setRoles(Set.of(role));

        Privilege privilege = new Privilege();
        privilege.setUuid(java.util.UUID.randomUUID());
        privilege.setName("testPrivilege");
        role.setPrivileges(Set.of(privilege));

        when(userService.findBySubject(username)).thenReturn(user);

        UserDetails userDetails = customUserDetailService.loadUserByUsername(username);
        assertNotNull(userDetails);
        assertInstanceOf(CustomUserDetails.class, userDetails);
    }

    @Test
    public void loadUserByUsername_withUser_notFound() {
        when(userService.findBySubject("nonexistent@example.com")).thenReturn(null);

        assertThrows(UsernameNotFoundException.class, () ->
                customUserDetailService.loadUserByUsername("nonexistent@example.com"));
    }
}
