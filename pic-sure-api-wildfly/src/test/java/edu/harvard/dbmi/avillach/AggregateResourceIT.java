package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.retrieveGetResponse;
import static edu.harvard.dbmi.avillach.service.HttpClientUtil.retrievePostResponse;
import static org.junit.Assert.*;

//Need tests executed in order to fill in variables for later tests
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AggregateResourceIT extends BaseIT {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    private final static String token = System.getProperty("irct.token");
    private final static String queryString = "{" +
            "    \"select\": [" +
            "        {" +
            "            \"alias\": \"gender\", \"field\": {\"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/SEX/male\", \"dataType\":\"STRING\"}" +
            "        }," +
            "        {" +
            "            \"alias\": \"gender\", \"field\": {\"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/SEX/female\", \"dataType\":\"STRING\"}" +
            "        }," +
            "        {" +
            "            \"alias\": \"age\",    \"field\": {\"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/AGE\", \"dataType\":\"STRING\"}" +
            "        }" +
            "    ]," +
            "    \"where\": [" +
            "        {" +
            "            \"predicate\": \"CONTAINS\"," +
            "            \"field\": {" +
            "                \"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/SEX/male/\"," +
            "                \"dataType\": \"STRING\"" +
            "            }," +
            "            \"fields\": {" +
            "                \"ENOUNTER\": \"YES\"" +
            "            }" +
            "        }" +
            "    ]" +
            "}";
    private final static String errorQuery = "{" +
            "    \"where\": [" +
            "        {" +
            "            \"predicate\": \"CONTAINS\"," +
            "            \"field\": {" +
            "                \"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/nonexistentpath\"," +
            "                \"dataType\": \"STRING\"" +
            "            }," +
            "            \"fields\": {" +
            "                \"ENOUNTER\": \"YES\"" +
            "            }" +
            "        }" +
            "    ]" +
            "}";
    private static UUID resourceUUID;
    private static UUID aggregateUUID;
    private static String queryId;
    private static String errorQueryId;
    private static String status;

    @BeforeClass
    public static void setUp() throws IOException{
        HttpResponse response = retrieveGetResponse(endpointUrl+"/info/resources", headers);
        assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
        List<JsonNode> responseBody = objectMapper.readValue(response.getEntity().getContent(), new TypeReference<List<JsonNode>>(){});
        assertFalse(responseBody.isEmpty());

        for (JsonNode node : responseBody){
            if (node.get("name").asText().equals("Aggregate Resource RS")){
                aggregateUUID = UUID.fromString(node.get("uuid").asText());
            } else if (!node.get("name").asText().equals("Test Resource")){
                resourceUUID = UUID.fromString(node.get("uuid").asText());
            }
        }
    }

    @Test
    public void testQuery() throws IOException {
        //Create multiple queries and add them to a main query as a list
        QueryRequest queryRequest1 = new QueryRequest();
        QueryRequest queryRequest2 = new QueryRequest();
        Map<String, String> credentials = new HashMap<String, String>();
        queryRequest1.setResourceCredentials(credentials);
        queryRequest1.setQuery(queryString);
        queryRequest1.setResourceUUID(resourceUUID);
        queryRequest2.setResourceCredentials(credentials);
        queryRequest2.setQuery(queryString);
        queryRequest2.setResourceUUID(resourceUUID);
        List<QueryRequest> queryList = new ArrayList<>();
        queryList.add(queryRequest1);
        queryList.add(queryRequest2);

        QueryRequest topQuery = new QueryRequest();
        topQuery.setQuery(queryList);
        topQuery.setResourceUUID(aggregateUUID);

        String body = objectMapper.writeValueAsString(topQuery);

        //Should throw an error if credentials missing or wrong
        HttpResponse response = retrievePostResponse(endpointUrl+"/query", headers, body);
        assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
        JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        String errorType = responseMessage.get("errorType").asText();
        assertEquals("Error type should be error", "error", errorType);
        String errorMessage = responseMessage.get("message").asText();
        assertTrue("Error message should be Unauthorized", errorMessage.contains("Unauthorized"));

        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anInvalidToken");
        queryRequest2.setResourceCredentials(credentials);
        body = objectMapper.writeValueAsString(topQuery);
        response = retrievePostResponse(endpointUrl+"/query", headers, body);
        assertEquals("Invalid credentials should return a 401", 401, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        errorType = responseMessage.get("errorType").asText();
        assertEquals("Error type should be error", "error", errorType);
        errorMessage = responseMessage.get("message").asText();
        assertTrue("Error message should be Unauthorized", errorMessage.contains("Unauthorized"));

        logger.info("Aggregate token is: " + token);
        //Should throw an error if missing query string
        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        queryRequest1.setResourceCredentials(credentials);
        queryRequest1.setQuery(null);
        queryRequest2.setResourceCredentials(credentials);
        body = objectMapper.writeValueAsString(topQuery);
        response = retrievePostResponse(endpointUrl+"/query", headers, body);
        assertEquals("Missing query should return a 500", 500, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);

        //Try a poorly worded queryString
        queryRequest1.setQuery("poorly worded query");
        body = objectMapper.writeValueAsString(topQuery);
        response = retrievePostResponse(endpointUrl+"/query", headers, body);
        assertEquals("Incorrectly formatted query should return a 500", 500, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);

        //Make sure all queries work
        queryRequest1.setQuery(queryString);
        body = objectMapper.writeValueAsString(topQuery);

        response = retrievePostResponse(endpointUrl+"/query", headers, body);
        assertEquals("Should return a 200", 200, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        assertNotNull("Status should not be null", responseMessage.get("status"));
        System.out.println("Aggregate response message from " + endpointUrl+"/query is: " + responseMessage.toString());
        queryId = responseMessage.get("picsureResultId").asText();
        System.out.println("Aggregate Resource IT, queryResultId is: " + queryId);
        assertNotNull("picsureResultId should not be null", queryId);

        //Want the status to be ERROR if one query errors - send query to be tested by queryStatus
        queryRequest2.setQuery(errorQuery);
        body = objectMapper.writeValueAsString(topQuery);
        response = retrievePostResponse(endpointUrl+"/query", headers, body);
        assertEquals("Should return a 200", 200, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        errorQueryId = responseMessage.get("picsureResultId").asText();
        assertNotNull("Status should not be null", responseMessage.get("status"));

    }

    @Test
    public void testQueryStatus() throws IOException {
        Map<String, String> credentials = new HashMap<String, String>();
        String body = objectMapper.writeValueAsString(credentials);

        //Should get 401 for missing or invalid credentials
        HttpResponse response = retrievePostResponse(endpointUrl+"/query/" + queryId + "/status", headers, body);
        assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
        JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
        System.out.println(("AggregateResourceIT - test missing or invalid credentials returns: " + responseMessage));
        assertNotNull("Response message should not be null", responseMessage);
        String errorType = responseMessage.get("errorType").asText();
        assertEquals("Error type should be error", "error", errorType);
        String errorMessage = responseMessage.get("message").asText();
        assertTrue("Error message should be Unauthorized", errorMessage.contains("Unauthorized"));

        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anInvalidToken");
        body = objectMapper.writeValueAsString(credentials);

        response = retrievePostResponse(endpointUrl+"/query/" + queryId + "/status", headers, body);
        assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        errorType = responseMessage.get("errorType").asText();
        assertEquals("Error type should be error", "error", errorType);
        errorMessage = responseMessage.get("message").asText();
        assertTrue("Error message should be Unauthorized", errorMessage.contains("Unauthorized"));

        //This should retrieve the status of the query successfully
        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        body = objectMapper.writeValueAsString(credentials);
        response = retrievePostResponse(endpointUrl+"/query/" + queryId + "/status", headers, body);
        assertEquals("Should return a 200", 200, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        status = responseMessage.get("status").asText();
        assertNotNull("Status should not be null", status);

        //This query should eventually result in an error, since one of the queries should have errored
        String errorStatus = PicSureStatus.PENDING.name();
        while (errorStatus.equals(PicSureStatus.PENDING.name())){
            response = retrievePostResponse(endpointUrl+"/query/" + errorQueryId + "/status", headers, body);
            assertEquals("Should return a 200", 200, response.getStatusLine().getStatusCode());
            responseMessage = objectMapper.readTree(response.getEntity().getContent());
            assertNotNull("Response message should not be null", responseMessage);
            errorStatus = responseMessage.get("status").asText();
        }
        assertEquals("Status should be ERROR", PicSureStatus.ERROR.name(), errorStatus);


    }

    @Test
    public void testResult() throws IOException, InterruptedException {
        Map<String, String> credentials = new HashMap<String, String>();
        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        String body = objectMapper.writeValueAsString(credentials);

        //Need to make sure result is ready
        while (!status.equals(PicSureStatus.AVAILABLE.name())){
            Thread.sleep(2000);
            HttpResponse response = retrievePostResponse(endpointUrl+"/query/" + queryId + "/status", headers, body);
            assertEquals("Should return a 200", 200, response.getStatusLine().getStatusCode());
            JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
            assertNotNull("Response message should not be null", responseMessage);
            status = responseMessage.get("status").asText();
        }
        credentials = new HashMap<String, String>();
        body = objectMapper.writeValueAsString(credentials);

        //Missing or invalid credentials should return 401
        HttpResponse response = retrievePostResponse(endpointUrl+"/query/" + queryId + "/result", headers, body);
        assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
        JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        String errorType = responseMessage.get("errorType").asText();
        assertEquals("Error type should be error", "error", errorType);
        String errorMessage = responseMessage.get("message").asText();
        assertTrue("Error message should be Unauthorized", errorMessage.contains("Unauthorized"));

        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anInvalidToken");
        body = objectMapper.writeValueAsString(credentials);

        response = retrievePostResponse(endpointUrl+"/query/" + queryId + "/result", headers, body);
        assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        errorType = responseMessage.get("errorType").asText();
        assertEquals("Error type should be error", "error", errorType);
        errorMessage = responseMessage.get("message").asText();
        assertTrue("Error message should be Unauthorized", errorMessage.contains("Unauthorized"));

        //Should return an array of results
        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        body = objectMapper.writeValueAsString(credentials);

        response = retrievePostResponse(endpointUrl+"/query/" + queryId + "/result", headers, body);
        assertEquals("Should return a 200", 200, response.getStatusLine().getStatusCode());
        responseMessage = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response message should not be null", responseMessage);
        //There were 2 queries so there should be 2 results
        assertEquals("There should be 2 results in the array", 2, responseMessage.size());

    }

}
