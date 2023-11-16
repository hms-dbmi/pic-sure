package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.composeURL;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.retrievePostResponse;
import static org.junit.Assert.*;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PicsureQueryServiceIT extends BaseIT {

    private final static String token = "fakeToken";

    private static String resultId;

    //This only needs to be a jsonnode, does not need to be a fully functional query
    private static final String queryString = "{  \"select\": \"alias\"        }";

    @Test
    public void testQuery() throws Exception {
        //We don't know what resource we fetched, so we don't want to actually try to reach it
        //Result to return
        HashMap<String, String> anyOldResult = new HashMap<>();
        //TODO: This might be different for different resources
        anyOldResult.put("resultId", "123abc");
        anyOldResult.put("status", "RUNNING");

        //TODO make this work for different resources
        wireMockRule.stubFor(any(urlPathMatching("/queryService/runQuery"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(objectMapper.writeValueAsString(anyOldResult))));

        wireMockRule.stubFor(any(urlPathMatching("/resultService/resultStatus/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(objectMapper.writeValueAsString(anyOldResult))));


        //Test missing info
        String uri = composeURL(endpointUrl, "/query/");
        QueryRequest dataQueryRequest = new GeneralQueryRequest();
        HttpResponse response = retrievePostResponse(uri, headers, "");
        assertEquals("Missing query request info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Test missing query string
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        dataQueryRequest.setResourceCredentials(clientCredentials);
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(dataQueryRequest));
        assertEquals("Missing query info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Test missing resourceId
        JsonNode jsonNode = objectMapper.readTree(queryString);
        dataQueryRequest.setQuery(jsonNode);
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(dataQueryRequest));
        assertEquals("Missing resource Id should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Test correct request
        dataQueryRequest.setResourceUUID(resourceId);
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(dataQueryRequest));
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());

        QueryStatus results = objectMapper.readValue(response.getEntity().getContent(), QueryStatus.class);
        assertNotNull("Status should not be null", results.getStatus());
        assertNotNull("Resource result id should not be null", results.getResourceResultId());
        assertNotNull("Resource Status should not be null", results.getResourceStatus());
        assertNotNull("Picsure result id should not be null", results.getPicsureResultId());
        //Store this resultId to use in subsequent tests
        resultId = results.getPicsureResultId().toString();
    }

    @Test
    public void testQueryStatus() throws Exception {

        QueryRequest statusRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        statusRequest.setResourceCredentials(clientCredentials);
        //Result to return
        HashMap<String, String> anyOldResult = new HashMap<>();
        //TODO: This might be different for different resources
        anyOldResult.put("resultId", "123abc");
        anyOldResult.put("status", "RUNNING");

        wireMockRule.stubFor(any(urlPathMatching("/resultService/resultStatus/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(objectMapper.writeValueAsString(anyOldResult))));

        //Test it without credentials
        String uri = composeURL(endpointUrl , "/query/"+resultId.toString() + "/status");
        HttpResponse response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(new HashMap<String, String>()));
        assertEquals("Missing credentials should return 401", 401, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(statusRequest));
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());
        QueryStatus results = objectMapper.readValue(response.getEntity().getContent(), QueryStatus.class);
        assertNotNull("Status should not be null", results.getStatus());
        assertNotNull("Resource result id should not be null", results.getResourceResultId());
        assertNotNull("Resource Status should not be null", results.getResourceStatus());

        //Nonexistent resultId
        uri = composeURL(endpointUrl , "/query/20f22062-f63b-4bca-919e-fcfd8d41d15c/status");
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(statusRequest));
        assertEquals("Nonexistent resultId should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Not a uuid
        uri = composeURL(endpointUrl , "/query/20f2241d15c/status");
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(statusRequest));
        assertEquals("Incorrectly formatted resultId should return 404", 404, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());
    }

    @Test
    public void testResult() throws Exception {

        wireMockRule.stubFor(any(urlPathMatching("/resultService/result/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("anyOldResultWillDo")));

        QueryRequest resultRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        resultRequest.setResourceCredentials(clientCredentials);

        String uri = composeURL(endpointUrl , "/query/"+resultId.toString() + "/result");
        HttpResponse response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(new HashMap<String, String>()));
        assertEquals("Missing credentials should return 401", 401, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //This should return some kind of result
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(resultRequest));
        assertEquals("Correct request should return a 200", 200, response.getStatusLine().getStatusCode());
        String result = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
        assertNotNull("Result should not be null, result");

        //Nonexistent resultId
        uri = composeURL(endpointUrl , "/query/20f22062-f63b-4bca-919e-fcfd8d41d15c/result");
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(resultRequest));
        assertEquals("Nonexistent resultId should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Not a uuid
        uri = composeURL(endpointUrl , "/query/20f2241d15c/result");
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(resultRequest));
        assertEquals("Incorrectly formatted resultId should return 404", 404, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());
    }

}
