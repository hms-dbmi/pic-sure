package edu.harvard.dbmi.avillach;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.junit.Test;

import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;


import org.apache.cxf.jaxrs.impl.ReaderInterceptorContextImpl;

import static org.junit.Assert.*;

public class LoggerReaderInterceptorTest {

    private LoggerReaderInterceptor cut = new LoggerReaderInterceptor();

    @Test
    public void testCredentialsRedacted() throws IOException{

        //Different pieces of a query
        String resourceCredentials = "resourceCredentials\": " +
                "{ \"IRCT_BEARER_TOKEN\": \"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU\"}";

        String query = "query\": " +
                "{  " +
                "\"select\": [" +
                "        {" +
                "            \"alias\": \"gender\", \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/SEX/male\", \"dataType\":\"STRING\"}" +
                "        }," +
                "        {" +
                "            \"alias\": \"gender\", \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/SEX/female\", \"dataType\":\"STRING\"}" +
                "        }," +
                "        {" +
                "            \"alias\": \"age\",    \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/AGE\", \"dataType\":\"STRING\"}" +
                "        }" +
                "    ]," +
                "    \"where\": [  " +
                "{   \"predicate\": \"CONTAINS\", " +
                "\"field\": " +
                "{  \"pui\": \"/nhanes/Demo/demographics/demographics/SEX/male/\",  " +
                "\"dataType\": \"STRING\" " +
                "},  " +
                "\"fields\": " +
                "{ \"ENOUNTER\": \"YES\" } " +
                "} ]" +
                "}";

        String resourceUUID = "\"resourceUUID\" : \"{{resourceUUID}}\"";

        //Assemble pieces into different test queries
        String order1 = "{ " + resourceUUID + "," + query + ", " + resourceCredentials + "}";
        String order2 = "{ " + query + "," + resourceCredentials + ", " + resourceUUID + "}";
        String credentialsOnly = "{" + resourceCredentials + "}";
        String nested1 = "{" + resourceUUID + ", query: " + order1 + ", " + resourceCredentials + "}";
        String nested2 = "{ " + resourceCredentials + "," + "query:" + nested1 + ", " + resourceUUID + "}";


        //Mocking turned out to be a mess so let's just do this this dumb way
        Message message = new MessageImpl();
        Exchange ex = new ExchangeImpl();
        ex.put("jaxrs.filter.properties", new HashMap<String, String>());
        message.setExchange(ex);

        //Put the test string into a stream and stuff it in a context to test
        InputStream stream = IOUtils.toInputStream(order1, "UTF-8");
        ReaderInterceptorContext context = new ReaderInterceptorContextImpl(null, null, null, stream,message, null);
        cut.aroundReadFrom(context);

        //See if it's done what we want
        Object result = context.getProperty("requestContent");
        assertNotNull(result);
        String resultString = result.toString();

        assertFalse(resultString.contains("resourceCredentials"));
        assertFalse(resultString.contains("IRCT_BEARER_TOKEN"));
        assertFalse(resultString.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(resultString.contains("RESOURCE_CREDENTIALS_REDACTED"));

        //Make sure order isn't an issue
        stream = IOUtils.toInputStream(order2, "UTF-8");
        context = new ReaderInterceptorContextImpl(null, null, null, stream,message, null);
        cut.aroundReadFrom(context);

        result = context.getProperty("requestContent");
        assertNotNull(result);
        resultString = result.toString();

        assertFalse(resultString.contains("resourceCredentials"));
        assertFalse(resultString.contains("IRCT_BEARER_TOKEN"));
        assertFalse(resultString.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(resultString.contains("RESOURCE_CREDENTIALS_REDACTED"));

        //What if there's nothing in there but credentials
        stream = IOUtils.toInputStream(credentialsOnly, "UTF-8");
        context = new ReaderInterceptorContextImpl(null, null, null, stream,message, null);
        cut.aroundReadFrom(context);

        result = context.getProperty("requestContent");
        assertNotNull(result);
        resultString = result.toString();

        assertFalse(resultString.contains("resourceCredentials"));
        assertFalse(resultString.contains("IRCT_BEARER_TOKEN"));
        assertFalse(resultString.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(resultString.contains("RESOURCE_CREDENTIALS_REDACTED"));

        //What if it's nested?
        stream = IOUtils.toInputStream(nested1, "UTF-8");
        context = new ReaderInterceptorContextImpl(null, null, null, stream,message, null);
        cut.aroundReadFrom(context);

        result = context.getProperty("requestContent");
        assertNotNull(result);
        resultString = result.toString();

        assertFalse(resultString.contains("resourceCredentials"));
        assertFalse(resultString.contains("IRCT_BEARER_TOKEN"));
        assertFalse(resultString.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(resultString.contains("RESOURCE_CREDENTIALS_REDACTED"));
        assertEquals(2, StringUtils.countMatches(resultString, "RESOURCE_CREDENTIALS_REDACTED"));

        //What if it's nested two layers deep??
        stream = IOUtils.toInputStream(nested2, "UTF-8");
        context = new ReaderInterceptorContextImpl(null, null, null, stream,message, null);
        cut.aroundReadFrom(context);

        result = context.getProperty("requestContent");
        assertNotNull(result);
        resultString = result.toString();

        assertFalse(resultString.contains("resourceCredentials"));
        assertFalse(resultString.contains("IRCT_BEARER_TOKEN"));
        assertFalse(resultString.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(resultString.contains("RESOURCE_CREDENTIALS_REDACTED"));
        assertEquals(3, StringUtils.countMatches(resultString, "RESOURCE_CREDENTIALS_REDACTED"));

    }

}
