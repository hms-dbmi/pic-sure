package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import javax.json.Json;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import static org.junit.Assert.*;

public class ResourceWebClientTest {

 //   protected static String endpointUrl;
    private final static ObjectMapper json = new ObjectMapper();
    //TODO what to do about this token
    private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
//    private final static String testURL = "http://localhost:8080/pic-sure-api-wildfly-2.0.0-SNAPSHOT/pic-sure/v1.4";
    private final static int port = 8079;
    private final static String testURL = "http://localhost:"+port;
    private final ResourceWebClient cut = new ResourceWebClient();

    @Rule
    public WireMockClassRule wireMockRule = new WireMockClassRule(port);

  /*  @BeforeClass
    public static void beforeClass() {
        endpointUrl = System.getProperty("service.url");
    }*/

    @Test
    public void testInfo() throws JsonProcessingException{
        String resourceInfo = json.writeValueAsString(new ResourceInfo());

        wireMockRule.stubFor(any(urlEqualTo("/info"))
                .willReturn(aResponse()
                .withStatus(200)
              .withBody(resourceInfo)));

        try {
            //Try it missing info
            ResourceInfo result = cut.info(testURL, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing credentials", e.getMessage());
        }
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);

        try {
            //Try it missing info
            ResourceInfo result = cut.info(null, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        ResourceInfo result = cut.info(testURL, credentials);
        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testSearch() throws JsonProcessingException{
        String searchResults = json.writeValueAsString(new SearchResults());

        wireMockRule.stubFor(any(urlEqualTo("/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(searchResults)));

        try {
            //Try it missing info
            SearchResults result = cut.search(testURL, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query request info", e.getMessage());
        }
        QueryRequest request = new QueryRequest();

        try {
            //Try it missing info
            SearchResults result = cut.search(null, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        SearchResults result = cut.search(testURL, request);
        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testQuery() throws JsonProcessingException{
        String queryResults = json.writeValueAsString(new QueryResults());

        wireMockRule.stubFor(any(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryResults)));

        try {
            //Try it missing info
            QueryResults result = cut.query(testURL, null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query request info", e.getMessage());
        }
        QueryRequest request = new QueryRequest();

        try {
            //Try it missing info
            QueryResults result = cut.query(null, request);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        QueryResults result = cut.query(testURL, request);
        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testQueryResult() throws JsonProcessingException{
        String queryResults = json.writeValueAsString(new QueryResults());

        wireMockRule.stubFor(any(urlMatching("/query/.*/result"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryResults)));

        try {
            //Try it missing info
            QueryResults result = cut.queryResult(testURL, new UUID(1,1), null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing credentials", e.getMessage());
        }
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);

        try {
            //Try it missing info
            QueryResults result = cut.queryResult(testURL, null, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query id", e.getMessage());
        }
        try {
            //Try it missing info
            QueryResults result = cut.queryResult(null, new UUID(1,1), credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        QueryResults result = cut.queryResult(testURL,new UUID(1,1), credentials);
        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testQueryStatus() throws JsonProcessingException{
        String queryStatus = json.writeValueAsString(new QueryStatus());

        wireMockRule.stubFor(any(urlMatching("/query/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(queryStatus)));

        try {
            //Try it missing info
            QueryStatus result = cut.queryStatus(testURL, new UUID(1,1), null);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing credentials", e.getMessage());
        }
        Map<String, String> credentials = new HashMap<>();
        credentials.put(ResourceWebClient.BEARER_TOKEN_KEY, token);

        try {
            //Try it missing info
            QueryStatus result = cut.queryStatus(testURL, null, credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing query id", e.getMessage());
        }
        try {
            //Try it missing info
            QueryStatus result = cut.queryStatus(null, new UUID(1,1), credentials);
            fail();
        } catch (Exception e) {
            //TODO Will change what this error actually says/does
            assertEquals("Missing resource URL", e.getMessage());
        }

        QueryStatus result = cut.queryStatus(testURL,new UUID(1,1), credentials);
        assertNotNull("Result should not be null", result);
    }
}
