package edu.harvard.dbmi.avillach;

import org.glassfish.jersey.internal.RuntimeDelegateImpl;
import org.junit.BeforeClass;

import org.junit.Ignore;

import javax.ws.rs.ext.RuntimeDelegate;

@Ignore
public class BaseServiceTest {

    protected static String endpointUrl;

    @BeforeClass
    public static void beforeClass() {
        endpointUrl = System.getProperty("service.url");

        //Need to be able to throw exceptions without container so we can verify correct errors are being thrown
        RuntimeDelegate runtimeDelegate = new RuntimeDelegateImpl();
        RuntimeDelegate.setInstance(runtimeDelegate);
}

}
