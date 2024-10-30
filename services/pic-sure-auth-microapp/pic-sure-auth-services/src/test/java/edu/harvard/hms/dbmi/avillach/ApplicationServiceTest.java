package edu.harvard.hms.dbmi.avillach;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.mockito.Mockito.spy;

@SpringBootTest
@ContextConfiguration(classes = {ApplicationService.class, JWTUtil.class})
public class ApplicationServiceTest {

    private ApplicationService applicationService;

    @MockBean
    private PrivilegeRepository privilegeRepository;

    @MockBean
    private ApplicationRepository applicationRepository;

    @BeforeEach
    public void init() {
        JWTUtil jwtUtil = spy(new JWTUtil(generate256Base64Secret(), false));
        applicationService = new ApplicationService(applicationRepository, privilegeRepository, jwtUtil);
    }

    @Test
    public void testGenerateToken() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("Testing Application");
        application.setUrl("https://psama.hms.harvard.edu");

        String token = applicationService.generateApplicationToken(application);

        Assertions.assertNotNull(token, "Token is null, given application: " + application.getUuid());
        Assertions.assertTrue(token.length() > 10, "Token is too short");
    }

    /**
     * Do not use this method in production code. This is only for testing purposes.
     * @return a 256-bit base64 encoded secret
     */
    private static String generate256Base64Secret() {
        SecureRandom random = new SecureRandom();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return Base64.getEncoder().encodeToString(secret);
    }
}
