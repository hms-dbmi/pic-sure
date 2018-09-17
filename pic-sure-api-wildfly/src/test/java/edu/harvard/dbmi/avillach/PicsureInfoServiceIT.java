package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.http.HttpResponse;
import org.junit.Test;

import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static edu.harvard.dbmi.avillach.service.HttpClientUtil.composeURL;
import static edu.harvard.dbmi.avillach.service.HttpClientUtil.retrievePostResponse;
import static org.junit.Assert.*;

public class PicsureInfoServiceIT extends BaseIT {

    @Test
    public void testInfoEndpoints() throws Exception {
        //We don't know what resource we fetched, so we don't want to actually try to reach it
        //Result to return
        List<QueryFormat> qfs = new ArrayList<>();

        //TODO be more specific with this url?
        wireMockRule.stubFor(any(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(objectMapper.writeValueAsString(qfs))));

        String uri = composeURL(endpointUrl, "/info/" + resourceId);

        HttpResponse response = retrievePostResponse(uri, headers, "");
        assertEquals("Missing credentials should return 401", 401, response.getStatusLine().getStatusCode());

        Map<String, String> clientCredentials = new HashMap<String, String>();
      //  clientCredentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        //TODO I guess we need some way to identify the token key?  Maybe V1.4_BEARER_TOKEN
        clientCredentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "testToken");
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(clientCredentials));
        assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
        ResourceInfo responseInfo = HttpClientUtil.readObjectFromResponse(response, ResourceInfo.class);
        assertNotNull("Resource response should not be null", responseInfo);
        assertNotNull("Resource response should have queryFormats", responseInfo.getQueryFormats());
        assertNotNull("Resource response should have a name", responseInfo.getName());

        //Try with a non-existent id
        uri = composeURL(endpointUrl , "/info/3b2437fe-df56-4360-8156-27bcf0b1a467");
        response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(clientCredentials));
        assertEquals("Incorrect resource Id should return 500", 500, response.getStatusLine().getStatusCode());
    }
}