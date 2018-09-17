package edu.harvard.dbmi.avillach;

import org.junit.BeforeClass;

import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.RuntimeDelegate;

@RunWith(MockitoJUnitRunner.class)
@Ignore
public class BaseServiceTest {

    protected static String endpointUrl;

    @BeforeClass
    public static void beforeClass() {
        endpointUrl = System.getProperty("service.url");

        //Need to be able to throw exceptions without container so we can verify correct errors are being thrown
        RuntimeDelegate runtimeDelegate = Mockito.mock(RuntimeDelegate.class);
        RuntimeDelegate.setInstance(runtimeDelegate);
        Response.ResponseBuilder responseBuilder = Mockito.mock(Response.ResponseBuilder.class);
        Response resp = Mockito.mock(Response.class);
        Mockito.when((runtimeDelegate).createResponseBuilder()).thenReturn(responseBuilder);
        Mockito.when(Response.status(Response.Status.INTERNAL_SERVER_ERROR)).thenReturn(responseBuilder);

}

}
