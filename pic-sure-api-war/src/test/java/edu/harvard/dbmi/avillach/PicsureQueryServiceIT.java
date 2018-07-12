package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class PicsureQueryServiceIT extends BaseIT{

    private static String resourceId;
    private static String jwt;
    private final static String queryString = "{" +
            "    \"select\": [" +
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
            "    \"where\": [\n" +
            "        {" +
            "            \"predicate\": \"CONTAINS\"," +
            "            \"field\": {" +
            "                \"pui\": \"/nhanes/Demo/demographics/demographics/SEX/male/\"," +
            "                \"dataType\": \"STRING\"" +
            "            }," +
            "            \"fields\": {" +
            "                \"ENOUNTER\": \"YES\"" +
            "            }" +
            "        }" +
            "    ]" +
            "}";

    @BeforeClass
    public static void beforeClass() throws Exception {
        endpointUrl = System.getProperty("service.url");
        //First we have to get the resourceId
        HttpClient client = HttpClientBuilder.create().build();
        String uri = endpointUrl + "/info/resources";
        HttpGet get = new HttpGet(uri);
        jwt = generateJwtForSystemUser();
        get.setHeader("Content-type","application/json");
        get.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        HttpResponse response = client.execute(get);
        assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
        List<JsonNode> responseBody = json.readValue(response.getEntity().getContent(), new TypeReference<List<JsonNode>>(){});
        assertFalse(responseBody.isEmpty());

        JsonNode firstResource = responseBody.get(0);
        resourceId = firstResource.get("uuid").asText();
        assertNotNull("Resource response should have an id", resourceId);
    }

    @Test
    public void testQuery() throws Exception {
        HttpClient client = HttpClientBuilder.create().build();

        //Test missing info
        String uri = endpointUrl + "/query/"+resourceId;
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-type","application/json");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        HttpResponse response = client.execute(post);
        assertEquals("Missing query request info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Test missing query string
        QueryRequest dataQueryRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        dataQueryRequest.setResourceCredentials(clientCredentials);
        post.setEntity(new StringEntity(json.writeValueAsString(dataQueryRequest)));
        response = client.execute(post);
        assertEquals("Missing query info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Test correct request
        JsonNode jsonNode = json.readTree(queryString);

        dataQueryRequest.setQuery(jsonNode);
        post.setEntity(new StringEntity(json.writeValueAsString(dataQueryRequest)));
        response = client.execute(post);
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());

        QueryStatus results = json.readValue(response.getEntity().getContent(), QueryStatus.class);
        assertNotNull("Status should not be null", results.getStatus());
        assertNotNull("Resource result id should not be null", results.getResourceResultId());
        assertNotNull("Resource Status should not be null", results.getResourceStatus());
        assertNotNull("Picsure result id should not be null", results.getPicsureResultId());
    }

    @Test
    public void testQueryStatus() throws Exception {
        HttpClient client = HttpClientBuilder.create().build();

        //Need to get a query ID first
        String uri = endpointUrl + "/query/"+resourceId;
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-type","application/json");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        QueryRequest dataQueryRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        dataQueryRequest.setResourceCredentials(clientCredentials);
        JsonNode jsonNode = json.readTree(queryString);

        dataQueryRequest.setQuery(jsonNode);
        post.setEntity(new StringEntity(json.writeValueAsString(dataQueryRequest)));
        HttpResponse response = client.execute(post);
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());
        QueryStatus results = json.readValue(response.getEntity().getContent(), QueryStatus.class);
        UUID resultId = results.getPicsureResultId();
        assertNotNull("Picsure result id should not be null", resultId);

        uri = endpointUrl + "/query/"+resultId.toString() + "/status";
        post = new HttpPost(uri);
        post.setHeader("Content-type","application/json");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        response = client.execute(post);
        assertEquals("Missing credentials should return 401", 401, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        post.setEntity(new StringEntity(json.writeValueAsString(clientCredentials)));
        response = client.execute(post);
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());
        results = json.readValue(response.getEntity().getContent(), QueryStatus.class);
        assertNotNull("Status should not be null", results.getStatus());
        assertNotNull("Resource result id should not be null", results.getResourceResultId());
        assertNotNull("Resource Status should not be null", results.getResourceStatus());

        //Nonexistent resultId
        uri = endpointUrl + "/query/20f22062-f63b-4bca-919e-fcfd8d41d15c/status";
        post.setURI(new URI(uri));
        response = client.execute(post);
        assertEquals("Nonexistent resultId should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Not a uuid
        uri = endpointUrl + "/query/20f2241d15c/status";
        post.setURI(new URI(uri));
        response = client.execute(post);
        assertEquals("Incorrectly formatted resultId should return 404", 404, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());
    }

    @Test
    public void testQueryResult() throws Exception {
        HttpClient client = HttpClientBuilder.create().build();

        //Need to get a query ID first
        String uri = endpointUrl + "/query/"+resourceId;
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-type","application/json");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        QueryRequest dataQueryRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        dataQueryRequest.setResourceCredentials(clientCredentials);
        JsonNode jsonNode = json.readTree(queryString);

        dataQueryRequest.setQuery(jsonNode);
        post.setEntity(new StringEntity(json.writeValueAsString(dataQueryRequest)));
        HttpResponse response = client.execute(post);
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());
        QueryStatus status = json.readValue(response.getEntity().getContent(), QueryStatus.class);
        UUID resultId = status.getPicsureResultId();
        assertNotNull("Picsure result id should not be null", resultId);

        uri = endpointUrl + "/query/"+resultId.toString() + "/result";
        post = new HttpPost(uri);
        post.setHeader("Content-type","application/json");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        response = client.execute(post);
        assertEquals("Missing credentials should return 401", 401, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        post.setEntity(new StringEntity(json.writeValueAsString(clientCredentials)));
        String results = null;

        //Need to give it time to complete query
        int i = 0;
        while (i < 10){
            response = client.execute(post);
            results = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            if (response.getStatusLine().getStatusCode() == 500){
                Thread.sleep(2000);
                i++;
            } else {
                i = 10;
            }
        }

        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());
        assertNotNull("Results should not be null", results);

        //Nonexistent resultId
        uri = endpointUrl + "/query/20f22062-f63b-4bca-919e-fcfd8d41d15c/status";
        post.setURI(new URI(uri));
        response = client.execute(post);
        assertEquals("Nonexistent resultId should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        //Not a uuid
        uri = endpointUrl + "/query/20f2241d15c/status";
        post.setURI(new URI(uri));
        response = client.execute(post);
        assertEquals("Incorrectly formatted resultId should return 404", 404, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());
    }

}
