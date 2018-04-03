package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class PicsureQueryServiceIT {

    private static String endpointUrl;
    private static String resourceId;
    private static String jwt;
    private final static ObjectMapper json = new ObjectMapper();
    private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
    private static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";
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
        jwt = generateJwtUser1();
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
        dataQueryRequest.setQuery(queryString);
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
        dataQueryRequest.setQuery(queryString);
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
        assertEquals("Missing query request info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        post.setEntity(null);
        response = client.execute(post);
        assertEquals("Missing credentials should return 500", 500, response.getStatusLine().getStatusCode());
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
        dataQueryRequest.setQuery(queryString);
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
        assertEquals("Missing query request info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        post.setEntity(null);
        response = client.execute(post);
        assertEquals("Missing credentials should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        post.setEntity(new StringEntity(json.writeValueAsString(clientCredentials)));
        response = client.execute(post);
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());
        String results = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
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

    public static String generateJwtUser1() {
        return Jwts.builder()
                .setSubject("samlp|foo@bar.com")
                .setIssuer("http://localhost:8080")
                .setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
                .setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(SignatureAlgorithm.HS512, Base64.getEncoder().encode("bar".getBytes()))
                .compact();
    }
}
