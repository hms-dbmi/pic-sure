package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.util.EntityUtils;
import org.glassfish.jersey.internal.RuntimeDelegateImpl;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.RuntimeDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import static org.junit.Assert.*;

@RunWith(MockitoJUnitRunner.class)
public class ResourceWebClientTest {

    private final static ObjectMapper json = new ObjectMapper();
    private final static String token = "testToken";
    private final static int port = 8079;
    private final static String testURL = "http://localhost:"+port;
    private final ResourceWebClient cut = new ResourceWebClient();

    @Rule
    public WireMockClassRule wireMockRule = new WireMockClassRule(port);

    @BeforeClass
    public static void beforeClass() {

        //Need to be able to throw exceptions without container so we can verify correct errors are being thrown
        RuntimeDelegate runtimeDelegate = new RuntimeDelegateImpl();
        RuntimeDelegate.setInstance(runtimeDelegate);
    }

    @Test
    public void testInfo() throws JsonProcessingException{
        String resourceInfo = json.writeValueAsString(new ResourceInfo());

        wireMockRule.stubFor(any(urlEqualTo("/info"))
                .willReturn(aResponse()
                .withStatus(200)
              .withBody(resourceInfo)));

        //Any targetURL that matches /info will trigger wiremock
        String targetURL = "/info";
        //Should throw an error if any parameters are missing
        try {
            cut.info(testURL, null);
            fail();
        } catch (ProtocolException e) {
            assertEquals(ProtocolException.MISSING_DATA, e.getContent());
        }
        GeneralQueryRequest queryRequest = new GeneralQueryRequest();
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        queryRequest.setResourceCredentials(credentials);
//        queryRequest.setTargetURL(targetURL);
        //Obviously should fail without the rsURL
        try {
            cut.info(null, queryRequest);
            fail();
        } catch (ApplicationException e) {
            assertEquals(ApplicationException.MISSING_RESOURCE_PATH, e.getContent());
        }

        //Should fail without a targetURL

//        queryRequest.setTargetURL(null);
//        try {
//            cut.info(testURL, queryRequest);
//            fail();
//        } catch (ApplicationException e) {
//            assertEquals(ApplicationException.MISSING_TARGET_URL, e.getContent());
//        }

        //Assuming everything goes right
//        queryRequest.setTargetURL(targetURL);
        ResourceInfo result = cut.info(testURL, queryRequest);
        assertNotNull("Result should not be null", result);

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlEqualTo("/info"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.info(testURL, queryRequest);
            fail();
        } catch (Exception e) {
            assertTrue( e.getMessage().contains("500 Server Error"));
        }

        //What if resource returns the wrong type of object for some reason?
        String incorrectResponse = json.writeValueAsString(new SearchResults());
        wireMockRule.stubFor(any(urlEqualTo("/info"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(incorrectResponse)));

        try {
            cut.info(testURL, queryRequest);
            fail();
        } catch (Exception e) {
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
        } catch (ProtocolException e) {
            assertEquals(ProtocolException.MISSING_DATA, e.getContent());
        }

        GeneralQueryRequest request = new GeneralQueryRequest();
        try {
            cut.search(testURL, request);
            fail();
        } catch (ProtocolException e) {
            assertEquals(ProtocolException.MISSING_DATA, e.getContent());
        }

        request.setQuery("query");

//        try {
//            cut.search(testURL, request);
//            fail();
//        } catch (ApplicationException e) {
//            assertEquals(ApplicationException.MISSING_TARGET_URL, e.getContent());
//        }

        String targetURL = "/search";
//        request.setTargetURL(targetURL);

        try {
            cut.search(null, request);
            fail();
        } catch (ApplicationException e) {
            assertEquals(ApplicationException.MISSING_RESOURCE_PATH, e.getContent());
        }

//        //Should fail if no credentials given
//        request.setQuery("query");
//        request.setTargetURL(targetURL);
//        try {
//            cut.search(testURL, request);
//            fail();
//        } catch (Exception e) {
//            assertEquals("HTTP 401 Unauthorized", e.getMessage());
//        }

        //With credentials but not search term
       /* Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        request.setQuery(null);
        request.setResourceCredentials(credentials);
        try {
            cut.search(testURL, request);
            fail();
        } catch (ProtocolException e) {
            assertEquals(ProtocolException.MISSING_DATA, e.getContent());
        }*/

        //Should fail with no targetURL
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        request.setResourceCredentials(credentials);
//        request.setTargetURL(null);
        request.setQuery("%blood%");
//        try {
//            cut.search(testURL, request);
//            fail();
//        } catch (ApplicationException e) {
//            assertEquals(ApplicationException.MISSING_TARGET_URL, e.getContent());
//        }

//        request.setTargetURL(targetURL);
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
            assertTrue( e.getMessage().contains("500 Server Error"));
        }

        //What if resource returns the wrong type of object for some reason?
        ResourceInfo incorrectResponse = new ResourceInfo();
        incorrectResponse.setName("resource name");
        incorrectResponse.setId(new UUID(1L, 1L));
        wireMockRule.stubFor(any(urlEqualTo("/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(json.writeValueAsString(incorrectResponse))));

        try {
            cut.search(testURL, request);
            fail();
        } catch (Exception e) {
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }

    @Test
    public void testQuery() throws JsonProcessingException{
        String queryResults = json.writeValueAsString(new QueryStatus());

        wireMockRule.stubFor(any(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryResults)));

        //Should fail if any parameters are missing
        try {
            cut.query(testURL, null);
            fail();
        } catch (ProtocolException e) {
            assertEquals(ProtocolException.MISSING_DATA, e.getContent());
        }
        GeneralQueryRequest request = new GeneralQueryRequest();
//        request.setTargetURL("/query");

        try {
            cut.query(null, request);
            fail();
        } catch (ApplicationException e) {
            assertEquals(ApplicationException.MISSING_RESOURCE_PATH, e.getContent());
        }

        //Should fail if no credentials given
//        try {
//            cut.query(testURL, request);
//            fail();
//        } catch (Exception e) {
//            assertEquals("HTTP 401 Unauthorized", e.getMessage());
//        }

        Map<String, String> credentials = new HashMap<>();
        request.setResourceCredentials(credentials);
//        request.setTargetURL(null);
        //Should fail without a targetURL
//        try {
//            cut.query(testURL, request);
//            fail();
//        } catch (ApplicationException e) {
//            assertEquals(ApplicationException.MISSING_TARGET_URL, e.getContent());
//        }

//        request.setTargetURL("/query");

        //Everything goes correctly
        QueryStatus result = cut.query(testURL, request);
        assertNotNull("Result should not be null", result);

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.query(testURL, request);
            fail();
        } catch (Exception e) {
            assertTrue( e.getMessage().contains("500 Server Error"));
        }

        //What if resource returns the wrong type of object for some reason?
        ResourceInfo incorrectResponse = new ResourceInfo();
        incorrectResponse.setName("resource name");
        incorrectResponse.setId(new UUID(1L, 1L));
        wireMockRule.stubFor(any(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(json.writeValueAsString(incorrectResponse))));

        try {
            cut.query(testURL, request);
            fail();
        } catch (Exception e) {
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }

    @Test
    public void testQueryResult() throws JsonProcessingException{
        String testId = "230048";
        String mockResult = "Any old response will work";



        wireMockRule.stubFor(any(urlMatching("/query/.*/result"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(mockResult)));

        //Should fail if missing any parameters
//        try {
//            cut.queryResult(testURL, testId, null);
//            fail();
//        } catch (ProtocolException e) {
//            assertEquals(ProtocolException.MISSING_DATA, e.getContent());
//        }
        GeneralQueryRequest queryRequest = new GeneralQueryRequest();
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        queryRequest.setResourceCredentials(credentials);
//        String targetURL = "/query/13452134/result";
////        queryRequest.setTargetURL(targetURL);
//
//        try {
//            cut.queryResult(testURL, null, queryRequest);
//            fail();
//        } catch (ProtocolException e) {
//            assertEquals(ProtocolException.MISSING_QUERY_ID, e.getContent());
//        }
//        try {
//            cut.queryResult(null, testId, queryRequest);
//            fail();
//        } catch (ApplicationException e) {
//            assertEquals(ApplicationException.MISSING_RESOURCE_PATH, e.getContent());
//        }

////        queryRequest.setTargetURL(null);
//        //Should fail without a targetURL
//        try {
//            cut.queryResult(testURL, testId, queryRequest);
//            fail();
//        } catch (ApplicationException e) {
//            assertEquals(ApplicationException.MISSING_TARGET_URL, e.getContent());
//        }
//
////        queryRequest.setTargetURL(targetURL);


        //Everything should work here
        Response result = cut.queryResult(testURL,testId, queryRequest);
        assertNotNull("Result should not be null", result);
        try {
            String resultContent = IOUtils.toString((InputStream) result.getEntity(), "UTF-8");
            assertEquals("Result should match " + mockResult, mockResult, resultContent);
        } catch (IOException e ){
            fail("Result content was unreadable");
        }

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlMatching("/query/.*/result"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.queryResult(testURL, testId, queryRequest);
            fail();
        } catch (Exception e) {
            assertTrue( e.getMessage().contains("500 Server Error"));
        }
    }

    @Test
    public void testQueryStatus() throws JsonProcessingException{
        String testId = "230048";
        QueryStatus testResult = new QueryStatus();
        testResult.setStatus(PicSureStatus.PENDING);
        testResult.setResourceStatus("RUNNING");
        String queryStatus = json.writeValueAsString(testResult);

        wireMockRule.stubFor(any(urlMatching("/query/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryStatus)));

        //Fails with any missing parameters
        try {
            cut.queryStatus(testURL, testId, null);
            fail();
        } catch (ProtocolException e) {
            assertEquals(ProtocolException.MISSING_DATA, e.getContent());
        }
        GeneralQueryRequest queryRequest = new GeneralQueryRequest();
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);
        queryRequest.setResourceCredentials(credentials);
//        String targetURL = "/query/13452134/result";
//        queryRequest.setTargetURL(targetURL);

        try {
            cut.queryStatus(testURL, null, queryRequest);
            fail();
        } catch (ProtocolException e) {
            assertEquals(ProtocolException.MISSING_QUERY_ID, e.getContent());
        }
        try {
            cut.queryStatus(null, testId, queryRequest);
            fail();
        } catch (ApplicationException e) {
            assertEquals(ApplicationException.MISSING_RESOURCE_PATH, e.getContent());
        }

//        queryRequest.setTargetURL(null);

        //Should fail without a targetURL
//        try {
//            cut.queryStatus(testURL, testId, queryRequest);
//            fail();
//        } catch (ApplicationException e) {
//            assertEquals(ApplicationException.MISSING_TARGET_URL, e.getContent());
//        }



//        queryRequest.setTargetURL(targetURL);

        //Everything should work here
        QueryStatus result = cut.queryStatus(testURL,testId, queryRequest);
        assertNotNull("Result should not be null", result);
        //Make sure all necessary fields are present
        assertNotNull("Duration should not be null",result.getDuration());
        assertNotNull("Expiration should not be null",result.getExpiration());
        assertNotNull("ResourceStatus should not be null",result.getResourceStatus());
        assertNotNull("Status should not be null",result.getStatus());

        //What if the resource has a problem?
        wireMockRule.stubFor(any(urlMatching("/query/.*/status"))
                .willReturn(aResponse()
                        .withStatus(500)));

        try {
            cut.queryStatus(testURL, testId, queryRequest);
            fail();
        } catch (Exception e) {
            assertTrue( e.getMessage().contains("500 Server Error"));
        }

        //What if resource returns the wrong type of object for some reason?
        ResourceInfo incorrect = new ResourceInfo();
        incorrect.setName("resource name");
        incorrect.setId(new UUID(1L, 1L));
        wireMockRule.stubFor(any(urlMatching("/query/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(json.writeValueAsString(incorrect))));

        try {
            cut.queryStatus(testURL, testId, queryRequest);
            fail();
        } catch (Exception e) {
            assertTrue( e.getMessage().contains("Incorrect object type returned"));
        }
    }
}
