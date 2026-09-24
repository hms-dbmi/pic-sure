package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ConsentAuthorizationConfigurationTest {

    @Test
    void applicationPropertyDefaultsConsentAuthorizationToTrue() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(input);
            properties.load(input);
        }

        assertEquals("${CONSENT_BASED_AUTHORIZATION_ENABLED:true}", properties.getProperty("consent.based.authorization.enabled"));
    }
}
