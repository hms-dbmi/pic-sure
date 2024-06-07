package edu.harvard.hms.dbmi.avillach;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.mockito.Mockito.spy;

@RunWith(MockitoJUnitRunner.class)
public class ApplicationServiceTest {

    @InjectMocks
    private ApplicationService applicationService;

    @Mock
    private PrivilegeRepository privilegeRepository;

    @Mock
    private ApplicationRepository applicationRepository;

    @Before
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

        Assert.assertNotNull("Token is null, given application: " + application.getUuid(), token);
        Assert.assertTrue("Token is too short",token.length() > 10);
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
