package edu.harvard.dbmi.avillach;

import org.glassfish.jersey.internal.RuntimeDelegateImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;

import javax.ws.rs.ext.RuntimeDelegate;

@Disabled
public class BaseServiceTest {

    protected static String endpointUrl;

    @BeforeAll
    public static void beforeClass() {
        endpointUrl = System.getProperty("service.url");

        // Need to be able to throw exceptions without container so we can verify correct errors are being thrown
        RuntimeDelegate runtimeDelegate = new RuntimeDelegateImpl();
        RuntimeDelegate.setInstance(runtimeDelegate);
    }

}
