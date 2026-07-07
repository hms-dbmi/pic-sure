package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CustomUserDetailService implements UserDetailsService {

    private final Logger logger = LoggerFactory.getLogger(CustomUserDetailService.class);

    private final UserService userService;
    private final ApplicationService applicationService;

    @Autowired
    public CustomUserDetailService(UserService userService, ApplicationService applicationService) {
        this.userService = userService;
        this.applicationService = applicationService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username.startsWith("application:")) {
            String applicationName = username.substring(12);
            Optional<Application> applicationByID = applicationService.getApplicationByIdWithPrivileges(applicationName);
            if (applicationByID.isEmpty()) {
                throw new UsernameNotFoundException("Application not found");
            }

            // Initialize the privileges of the application proxy object to avoid lazy loading exception
            Hibernate.initialize(applicationByID.get().getPrivileges());
            return new CustomApplicationDetails(applicationByID.get());
        } else {
            logger.info("Loading user by username: {}", username);
            User user = this.userService.findBySubject(username);
            if (user == null) {
                throw new UsernameNotFoundException("User not found with email: " + username);
            }

            return new CustomUserDetails(user);
        }
    }
}
