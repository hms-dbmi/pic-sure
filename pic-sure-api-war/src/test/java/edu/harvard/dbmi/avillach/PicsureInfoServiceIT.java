package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.Test;

import javax.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.util.*;

import static org.junit.Assert.*;

public class PicsureInfoServiceIT extends BaseIT {

    @Test
    public void testInfoEndpoints() throws Exception {
        HttpClient client = HttpClientBuilder.create().build();
        String uri = endpointUrl + "/info/resources";
        HttpGet get = new HttpGet(uri);
        String jwt = generateJwtForSystemUser();
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
        assertEquals("Missing credentials should return 401", 401, response.getStatusLine().getStatusCode());

        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        post.setEntity(new StringEntity(json.writeValueAsString(clientCredentials)));
        response = client.execute(post);
        assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
        ResourceInfo responseInfo = HttpClientUtil.readObjectFromResponse(response, ResourceInfo.class);
        assertNotNull("Resource response should not be null", responseInfo);
        assertNotNull("Resource response should have queryFormats", responseInfo.getQueryFormats());
        assertNotNull("Resource response should have a name", responseInfo.getName());

        //Try with a non-existent id
        uri = endpointUrl + "/info/3b2437fe-df56-4360-8156-27bcf0b1a467";
        post.setURI(new URI(uri));
        response = client.execute(post);
        assertEquals("Incorrect resource Id should return 500", 500, response.getStatusLine().getStatusCode());
        }
}
