package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import org.junit.Rule;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import static org.junit.Assert.*;

public class ResourceWebClientTest {

    private final static ObjectMapper json = new ObjectMapper();
    //TODO what to do about this token
//    private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
    private final static String token = "testToken";
    private final static int port = 8079;
    private final static String testURL = "http://localhost:"+port;
    private final ResourceWebClient cut = new ResourceWebClient();

    @Rule
    public WireMockClassRule wireMockRule = new WireMockClassRule(port);

    @Test
    public void testInfo() throws JsonProcessingException{
        String resourceInfo = json.writeValueAsString(new ResourceInfo());

        wireMockRule.stubFor(any(urlEqualTo("/info"))
                .willReturn(aResponse()
                .withStatus(200)
              .withBody(resourceInfo)));

        //Should throw an error if any parameters are missing
        try {
            cut.info(testURL, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing credentials", e.getMessage());
        }
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        try {
            cut.info(null, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        //Assuming everything goes right
        ResourceInfo result = cut.info(testURL, credentials);
        assertNotNull("Result should not be null", result);

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlEqualTo("/info"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.info(testURL, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Resource returned 500"));
        }

        //What if resource returns the wrong type of object for some reason?
        String incorrectResponse = json.writeValueAsString(new SearchResults());
        wireMockRule.stubFor(any(urlEqualTo("/info"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(incorrectResponse)));

        try {
            cut.info(testURL, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }

    @Test
    public void testSearch() throws JsonProcessingException{
        String searchResults = json.writeValueAsString(new SearchResults());

        wireMockRule.stubFor(any(urlEqualTo("/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(searchResults)));

        //Should throw an error if any parameters are missing
        try {
            cut.search(testURL, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query request info", e.getMessage());
        }
        QueryRequest request = new QueryRequest();
        try {
            cut.search(null, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        //If everything goes right
        SearchResults result = cut.search(testURL, request);
        assertNotNull("Result should not be null", result);

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlEqualTo("/search"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.search(testURL, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Resource returned 500"));
        }

        //What if resource returns the wrong type of object for some reason?
        String incorrectResponse = json.writeValueAsString(new ResourceInfo());
        wireMockRule.stubFor(any(urlEqualTo("/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(incorrectResponse)));

        try {
            cut.search(testURL, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }

    @Test
    public void testQuery() throws JsonProcessingException{
        String queryResults = json.writeValueAsString(new QueryResults());

        wireMockRule.stubFor(any(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryResults)));

        //Should fail if any parameters are missing
        try {
            cut.query(testURL, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query request info", e.getMessage());
        }
        QueryRequest request = new QueryRequest();
        try {
            cut.query(null, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        //Everything goes correctly
        QueryResults result = cut.query(testURL, request);
        assertNotNull("Result should not be null", result);

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.query(testURL, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Resource returned 500"));
        }

        //What if resource returns the wrong type of object for some reason?
        String incorrectResponse = json.writeValueAsString(new ResourceInfo());
        wireMockRule.stubFor(any(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(incorrectResponse)));

        try {
            cut.query(testURL, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }

    @Test
    public void testQueryResult() throws JsonProcessingException{
        String queryResults = json.writeValueAsString(new QueryResults());
        UUID testUUID = new UUID(1, 1);

        wireMockRule.stubFor(any(urlMatching("/query/.*/result"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryResults)));

        //Should fail if missing any parameters
        try {
            cut.queryResult(testURL, testUUID, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing credentials", e.getMessage());
        }
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        try {
            cut.queryResult(testURL, null, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query id", e.getMessage());
        }
        try {
            cut.queryResult(null, testUUID, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        //Everything should work here
        QueryResults result = cut.queryResult(testURL,testUUID, credentials);
        assertNotNull("Result should not be null", result);

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlMatching("/query/.*/result"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.queryResult(testURL, testUUID, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Resource returned 500"));
        }

        //What if resource returns the wrong type of object for some reason?
        String incorrectResponse = json.writeValueAsString(new ResourceInfo());
        wireMockRule.stubFor(any(urlMatching("/query/.*/result"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(incorrectResponse)));

        try {
            cut.queryResult(testURL, testUUID, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }

    @Test
    public void testQueryStatus() throws JsonProcessingException{
        String queryStatus = json.writeValueAsString(new QueryStatus());
        UUID testUUID = new UUID(1, 1);

        wireMockRule.stubFor(any(urlMatching("/query/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryStatus)));

        //Fails with any missing parameters
        try {
            cut.queryStatus(testURL, testUUID, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing credentials", e.getMessage());
        }
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        try {
            cut.queryStatus(testURL, null, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query id", e.getMessage());
        }
        try {
            cut.queryStatus(null, testUUID, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        //Everything should work here
        QueryStatus result = cut.queryStatus(testURL,testUUID, credentials);
        assertNotNull("Result should not be null", result);

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlMatching("/query/.*/status"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.queryStatus(testURL, testUUID, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Resource returned 500"));
        }

        //What if resource returns the wrong type of object for some reason?
        String incorrectResponse = json.writeValueAsString(new ResourceInfo());
        wireMockRule.stubFor(any(urlMatching("/query/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(incorrectResponse)));

        try {
            cut.queryStatus(testURL, testUUID, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }
}
