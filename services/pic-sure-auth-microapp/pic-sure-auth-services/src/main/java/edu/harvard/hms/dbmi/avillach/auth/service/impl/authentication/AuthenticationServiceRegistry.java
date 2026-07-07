package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AuthenticationServiceRegistry {

    private final Logger logger = LoggerFactory.getLogger(AuthenticationServiceRegistry.class);

    private final Map<String, AuthenticationService> authenticationServices = new HashMap<>();

    @Autowired
    public AuthenticationServiceRegistry(List<AuthenticationService> authenticationServices) {
        authenticationServices.forEach(authenticationService -> {
            logger.info("Registering authentication service: {}", authenticationService.getProvider());
            if (authenticationService.isEnabled()) {
                this.authenticationServices.put(authenticationService.getProvider(), authenticationService);
                logger.info("Registered authentication service: {}", authenticationService.getProvider());
            } else {
                logger.info("Skipping registration of disabled authentication service: {}", authenticationService.getProvider());
            }
        });
    }

    public AuthenticationService getAuthenticationService(String provider) {
        // Check if the provider is registered by the provider name
        if (!authenticationServices.containsKey(provider)) {
            throw new IllegalArgumentException("No authentication service found for provider: " + provider);
        }

        return authenticationServices.get(provider);
    }

}
