package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.core.HttpHeaders;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.Assert.*;

public class PicsureInfoServiceIT {

    private static String endpointUrl;
    private final static ObjectMapper json = new ObjectMapper();
    private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
    private static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";



    @BeforeClass
    public static void beforeClass() {
        endpointUrl = System.getProperty("service.url");
    }

    @Test
    public void testInfoEndpoints() throws Exception {
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
        String id = firstResource.get("uuid").asText();
        assertNotNull("Resource response should have an id", id);

        uri = endpointUrl + "/info/" + id;
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-type","application/json");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        response = client.execute(post);
        assertEquals("Missing credentials should return 500", 500, response.getStatusLine().getStatusCode());

        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        post.setEntity(new StringEntity(json.writeValueAsString(clientCredentials)));
        response = client.execute(post);
        assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
        ResourceInfo responseInfo = HttpClientUtil.readObjectFromResponse(response, ResourceInfo.class);
        assertNotNull("Resource response should not be null", responseInfo);
        assertNotNull("Resource response should have queryFormats", responseInfo.getQueryFormats());
        assertNotNull("Resource response should have a name", responseInfo.getName());

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
