package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class PicsureSearchServiceIT {

    private static String endpointUrl;
    private final static ObjectMapper json = new ObjectMapper();
    private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
    private static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";

    @BeforeClass
    public static void beforeClass() {
        endpointUrl = System.getProperty("service.url");
    }

    @Test
    public void testSearch() throws Exception {
        //First we have to get the resourceId
        HttpClient client = HttpClientBuilder.create().build();
        String uri = endpointUrl + "/info/resources";
        HttpGet get = new HttpGet(uri);
        String jwt = generateJwtUser1();
        get.setHeader("Content-type","application/json");
        get.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        HttpResponse response = client.execute(get);
        assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
        List<JsonNode> responseBody = json.readValue(response.getEntity().getContent(), new TypeReference<List<JsonNode>>(){});
        assertFalse(responseBody.isEmpty());

        JsonNode firstResource = responseBody.get(0);
        String resourceId = firstResource.get("uuid").asText();
        assertNotNull("Resource response should have an id", resourceId);

        uri = endpointUrl + "/search/"+resourceId;
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-type","application/json");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        response = client.execute(post);
        assertEquals("Missing query request info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        QueryRequest searchQueryRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        searchQueryRequest.setResourceCredentials(clientCredentials);
        post.setEntity(new StringEntity(json.writeValueAsString(searchQueryRequest)));
        response = client.execute(post);
        assertEquals("Missing query search info should return 500", 500, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        searchQueryRequest.setQuery("blood");
        post.setEntity(new StringEntity(json.writeValueAsString(searchQueryRequest)));
        response = client.execute(post);
        assertEquals("Response should be 200", 200, response.getStatusLine().getStatusCode());

        SearchResults results = json.readValue(response.getEntity().getContent(), SearchResults.class);
        assertNotNull("Results should not be null", results.getResults());
        assertEquals("Searchquery should match input query", results.getSearchQuery(), "blood");

    }

    public String generateJwtUser1() {
        return Jwts.builder()
                .setSubject("samlp|foo@bar.com")
                .setIssuer("http://localhost:8080")
                .setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
                .setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(SignatureAlgorithm.HS512, Base64.getEncoder().encode("bar".getBytes()))
                .compact();
    }
}
