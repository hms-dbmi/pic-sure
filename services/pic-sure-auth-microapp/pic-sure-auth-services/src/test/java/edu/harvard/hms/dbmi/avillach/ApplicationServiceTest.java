package edu.harvard.hms.dbmi.avillach;

import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.rest.ApplicationService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

public class ApplicationServiceTest {

    @Before
    public void init() {
        JAXRSConfiguration.clientSecret = "test";
    }

    @Test
    public void testGenerateToken(){

        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("Testing Application");

        String token = new ApplicationService().generateApplicationToken(application);

        Assert.assertNotNull("Token is null, given application: " + application.getUuid(), token);
        Assert.assertTrue("Token is too short",token.length() > 10);
    }
}
